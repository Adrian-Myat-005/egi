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

// --- PURE KERNEL MODE: RELY ON ANDROID VPN SERVICE ROUTING ---
// The filtering is now handled by the Android Kernel via VpnService.Builder
// This Rust engine focuses solely on high-speed packet shuttling.

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

// Deprecated: Passive Shield is being phased out for Active Monitor
// But kept as a simple sink for compatibility if needed.
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
                // Just drain the buffer. The "Block" happens because we didn't route traffic here.
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
    crate::log_to_java("VPN >> STARTING_ENGINE");
    
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
        let ss_key = secure_key.key.clone();

        let ss_local_addr = local_addr_str.clone();
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
                        crate::log_to_java(&format!("VPN >> SOCKS5_READY: {}", ss_local_addr));
                        if let Err(e) = run_ss_local(config).await {
                            crate::log_to_java(&format!("VPN >> SS_ERR: {}", e));
                        }
                    }
                }
                Err(e) => {
                    crate::log_to_java(&format!("VPN >> INVALID_KEY: {}", e));
                }
            }
        });

        // Fast Start: Wait max 1.5s for SOCKS5
        let mut proxy_ready = false;
        for _ in 0..5 {
            tokio::time::sleep(Duration::from_millis(300)).await;
            if tokio::net::TcpStream::connect(local_addr_str.clone()).await.is_ok() {
                proxy_ready = true;
                break;
            }
        }

        if !proxy_ready {
            crate::log_to_java("VPN >> ERR: PROXY_TIMEOUT");
            CORE_STATUS.store(3, Ordering::SeqCst);
            return;
        }
        
        let mut tun_config = tun::Configuration::default();
        tun_config.raw_fd(fd);
        
        match tun::create_as_async(&tun_config) {
            Ok(tun_device) => {
                CORE_STATUS.store(2, Ordering::SeqCst);
                if let Ok(proxy) = ArgProxy::try_from(format!("socks5://{}", local_addr_str).as_str()) {
                    let token = CancellationToken::new();
                    let mut args = Args::default();
                    args.proxy = proxy;
                    args.dns = ArgDns::Virtual;
                    args.verbosity = ArgVerbosity::Off;
                    
                    crate::log_to_java("VPN >> TUNNEL_ESTABLISHED");

                    let monitor_token = token.clone();
                    tokio::spawn(async move {
                        while CORE_STATUS.load(Ordering::SeqCst) != 0 {
                            tokio::time::sleep(Duration::from_secs(2)).await;
                        }
                        monitor_token.cancel();
                    });

                    // Direct pipe: No more FilteredTun nonsense.
                    if let Err(e) = run_tun2proxy(tun_device, 1280, args, token).await {
                        crate::log_to_java(&format!("VPN >> EXIT: {}", e));
                    }
                }
            }
            Err(e) => {
                crate::log_to_java(&format!("VPN >> TUN_FAIL: {}", e));
                CORE_STATUS.store(3, Ordering::SeqCst);
            }
        }
        CORE_STATUS.store(0, Ordering::SeqCst);
    });
}
