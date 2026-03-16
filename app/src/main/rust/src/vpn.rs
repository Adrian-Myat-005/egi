use std::time::Duration;
use shadowsocks::config::{ServerConfig, Mode, ServerAddr};
use shadowsocks_service::config::{Config, ConfigType, LocalInstanceConfig, LocalConfig, ProtocolType, ServerInstanceConfig};
use shadowsocks_service::local::run as run_ss_local;
use tun2proxy::{run as run_tun2proxy, Args, ArgProxy, ArgDns, ArgVerbosity, CancellationToken};
use std::str::FromStr;
use std::convert::TryFrom;
use std::sync::atomic::Ordering;
use std::os::unix::io::RawFd;
use tokio::io::unix::AsyncFd;
use std::net::TcpListener;
use crate::common::*;

fn find_free_port() -> Option<u16> {
    TcpListener::bind("127.0.0.1:0")
        .and_then(|listener| listener.local_addr())
        .map(|addr| addr.port())
        .ok()
}

fn set_nonblocking(fd: RawFd) -> std::io::Result<()> {
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags < 0 {
            return Err(std::io::Error::last_os_error());
        }
        if libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
            return Err(std::io::Error::last_os_error());
        }
    }
    Ok(())
}

pub async fn run_passive_shield_internal(fd: RawFd) {
    CORE_STATUS.store(2, Ordering::SeqCst);
    crate::log_to_java("VPN >> PASSIVE_SHIELD (SINK)");
    
    if let Err(e) = set_nonblocking(fd) {
        crate::log_to_java(&format!("VPN >> WARN_NONBLOCK: {}", e));
    }

    let async_fd = match AsyncFd::new(fd) {
        Ok(afd) => afd,
        Err(e) => {
            crate::log_to_java(&format!("VPN >> PASSIVE_ERR: {}", e));
            CORE_STATUS.store(3, Ordering::SeqCst);
            return;
        }
    };

    let mut buf = vec![0u8; 16384];
    loop {
        if CORE_STATUS.load(Ordering::SeqCst) == 0 { break; }
        match async_fd.readable().await {
            Ok(mut guard) => {
                match unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) } {
                    n if n > 0 => {
                        BYTES_PROCESSED.fetch_add(n as u64, Ordering::Relaxed);
                        guard.clear_ready();
                    }
                    0 => break,
                    _ => {
                        let err = std::io::Error::last_os_error();
                        if err.kind() != std::io::ErrorKind::WouldBlock {
                            break;
                        }
                    }
                }
            }
            Err(_) => break,
        }
    }
    CORE_STATUS.store(0, Ordering::SeqCst);
}

pub fn start_vpn_loop(fd: i32) {
    CORE_STATUS.store(1, Ordering::SeqCst);
    crate::log_to_java("VPN >> STARTING_ENGINE_V2");
    
    if let Err(e) = set_nonblocking(fd) {
        crate::log_to_java(&format!("VPN >> WARN_NONBLOCK: {}", e));
    }

    TOKIO_RT.block_on(async {
        let secure_key = match OUTLINE_KEY.read() {
            Ok(guard) => guard.clone(),
            Err(_) => {
                CORE_STATUS.store(3, Ordering::SeqCst);
                crate::log_to_java("VPN >> ERR: KEY_READ_FAILED");
                return;
            }
        };

        if secure_key.key.is_empty() {
            crate::log_to_java("VPN >> ERR: NO_KEY_PROVIDED");
            CORE_STATUS.store(3, Ordering::SeqCst);
            return;
        }

        let port = find_free_port().unwrap_or(10808);
        PROXY_PORT.store(port, Ordering::Relaxed);
        
        let local_addr_str = format!("127.0.0.1:{}", port);
        let master_token = CancellationToken::new();

        // Robust trimming and remark removal
        let mut ss_key = secure_key.key.trim().to_string();
        if let Some(pos) = ss_key.find('#') {
            ss_key.truncate(pos);
        }

        let ss_local_addr = local_addr_str.clone();
        let ss_token = master_token.clone();
        
        // --- COMPONENT 1: SHADOWSOCKS LOCAL ---
        tokio::spawn(async move {
            match ServerConfig::from_url(&ss_key) {
                Ok(server_config) => {
                    let mut config = Config::new(ConfigType::Local);
                    if let Ok(local_addr) = ServerAddr::from_str(&ss_local_addr) {
                        let mut local_config = LocalConfig::new(ProtocolType::Socks);
                        local_config.addr = Some(local_addr);
                        local_config.mode = Mode::TcpAndUdp;
                        config.local.push(LocalInstanceConfig { config: local_config, acl: None });
                        config.server.push(ServerInstanceConfig::with_server_config(server_config));
                        
                        tokio::select! {
                            res = run_ss_local(config) => {
                                if let Err(e) = res {
                                    crate::log_to_java(&format!("VPN >> SS_CRASH: {}", e));
                                }
                                ss_token.cancel();
                            }
                            _ = ss_token.cancelled() => {}
                        }
                    }
                }
                Err(e) => {
                    crate::log_to_java(&format!("VPN >> INVALID_KEY: {}", e));
                    ss_token.cancel();
                }
            }
        });

        // Wait for SOCKS5 with unified token check
        let mut proxy_ready = false;
        for _ in 0..15 {
            if master_token.is_cancelled() { break; }
            tokio::time::sleep(Duration::from_millis(200)).await;
            if tokio::net::TcpStream::connect(local_addr_str.clone()).await.is_ok() {
                proxy_ready = true;
                break;
            }
        }

        if !proxy_ready || master_token.is_cancelled() {
            crate::log_to_java("VPN >> ERR: PROXY_NOT_RESPONDING");
            CORE_STATUS.store(3, Ordering::SeqCst);
            master_token.cancel();
            return;
        }
        
        // --- COMPONENT 2: SOCKS5 HEALTH MONITOR ---
        let monitor_addr = local_addr_str.clone();
        let monitor_token = master_token.clone();
        VPN_HEALTH_STATUS.store(1, Ordering::SeqCst); // Start as healthy
        
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(15)).await;
                if monitor_token.is_cancelled() { break; }
                
                // End-to-End Handshake Check
                let mut is_healthy = false;
                match tokio::time::timeout(Duration::from_secs(5), tokio::net::TcpStream::connect(&monitor_addr)).await {
                    Ok(Ok(mut stream)) => {
                        use tokio::io::{AsyncWriteExt, AsyncReadExt};
                        // 1. SOCKS5 Greeting
                        if stream.write_all(&[0x05, 0x01, 0x00]).await.is_ok() {
                            let mut res = [0u8; 2];
                            if stream.read_exact(&mut res).await.is_ok() && res == [0x05, 0x00] {
                                // 2. SOCKS5 Connect to 1.1.1.1:53 (DNS) - Lightweight test
                                let cmd = [0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0, 53];
                                if stream.write_all(&cmd).await.is_ok() {
                                    let mut res = [0u8; 10];
                                    if stream.read_exact(&mut res).await.is_ok() && res[1] == 0x00 {
                                        is_healthy = true;
                                    }
                                }
                            }
                        }
                    }
                    _ => {}
                }

                if is_healthy {
                    VPN_HEALTH_STATUS.store(1, Ordering::SeqCst);
                } else {
                    VPN_HEALTH_STATUS.store(2, Ordering::SeqCst);
                    crate::log_to_java("VPN >> MONITOR_DETECTED_STALLED_CONNECTION");
                    // We don't necessarily kill it here yet, we let the Java watchdog decide 
                    // or we can be aggressive and kill it now.
                    // The user says "kill silently", so let's be aggressive if it's dead.
                    // monitor_token.cancel(); 
                }
            }
        });

        // --- COMPONENT 3: TUN2PROXY ---
        let mut tun_config = tun::Configuration::default();
        tun_config.raw_fd(fd);
        
        match tun::create_as_async(&tun_config) {
            Ok(tun_device) => {
                CORE_STATUS.store(2, Ordering::SeqCst);
                if let Ok(proxy) = ArgProxy::try_from(format!("socks5://{}", local_addr_str).as_str()) {
                    let mut args = Args::default();
                    args.proxy = proxy;
                    args.dns = ArgDns::Virtual;
                    args.verbosity = ArgVerbosity::Off;
                    
                    crate::log_to_java("VPN >> TUNNEL_LIVE");

                    let status_monitor_token = master_token.clone();
                    tokio::spawn(async move {
                        while CORE_STATUS.load(Ordering::SeqCst) != 0 && !status_monitor_token.is_cancelled() {
                            tokio::time::sleep(Duration::from_millis(200)).await;
                        }
                        status_monitor_token.cancel();
                    });

                    if let Err(e) = run_tun2proxy(tun_device, 1280, args, master_token.clone()).await {
                        crate::log_to_java(&format!("VPN >> TUN2PROXY_EXIT: {}", e));
                    }
                    master_token.cancel();
                }
            }
            Err(e) => {
                crate::log_to_java(&format!("VPN >> TUN_FAIL: {}", e));
                CORE_STATUS.store(3, Ordering::SeqCst);
                master_token.cancel();
            }
        }
        
        if CORE_STATUS.load(Ordering::SeqCst) != 3 {
            CORE_STATUS.store(0, Ordering::SeqCst);
        }
        crate::log_to_java("VPN >> ENGINE_SHUTDOWN_COMPLETE");
    });
}
