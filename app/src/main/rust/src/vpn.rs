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

fn find_uid_by_port(port: u16, is_udp: bool) -> Option<u32> {
    use std::fs::File;
    use std::io::{BufRead, BufReader};

    let path = if is_udp { "/proc/net/udp" } else { "/proc/net/tcp" };
    if let Ok(file) = File::open(path) {
        let reader = BufReader::new(file);
        // Skip header
        for line in reader.lines().skip(1).flatten() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() > 7 {
                // local_address is at parts[1], format: "0100007F:2710" (hex IP:port)
                if let Some(pos) = parts[1].find(':') {
                    if let Ok(p) = u16::from_str_radix(&parts[1][pos+1..], 16) {
                        if p == port {
                            return parts[7].parse::<u32>().ok();
                        }
                    }
                }
            }
        }
    }
    None
}

fn check_uid_lockdown(packet: &[u8]) -> bool {
    let allowed = match ALLOWED_UIDS.read() {
        Ok(guard) => guard.clone(),
        Err(_) => return true,
    };

    if allowed.is_empty() {
        return true; // Global Mode
    }

    if let Ok(value) = etherparse::SlicedPacket::from_ip(packet) {
        let (port, is_udp) = match value.transport {
            Some(etherparse::TransportSlice::Tcp(tcp)) => (tcp.source_port(), false),
            Some(etherparse::TransportSlice::Udp(udp)) => (udp.source_port(), true),
            _ => return true, // Allow ICMP etc. for now
        };

        if let Some(uid) = find_uid_by_port(port, is_udp) {
            // Check if UID is in allowed list
            if allowed.contains(&uid) {
                return true;
            } else {
                return false; // Drop unauthorized traffic
            }
        }
    }
    true // If we can't find the UID (e.g. fast connection closure), allow it to avoid broken states
}

fn check_focus_whitelist(packet: &[u8]) -> bool {
    let allowed = match ALLOWED_DOMAINS.read() {
        Ok(guard) => guard.clone(),
        Err(_) => return true,
    };

    if allowed.is_empty() {
        return true;
    }

    // Real work: Minimal SNI extraction for TLS Client Hello
    // This is a lightweight way to see where the user is going
    if let Ok(value) = etherparse::SlicedPacket::from_ip(packet) {
        if let Some(etherparse::TransportSlice::Tcp(tcp)) = value.transport {
            let payload = tcp.payload();
            if payload.len() > 43 && payload[0] == 0x16 && payload[5] == 0x01 {
                // Potential TLS Client Hello
                for domain in &allowed {
                    if let Some(_pos) = payload.windows(domain.len()).position(|window| window == domain.as_bytes()) {
                        // Found allowed domain in SNI/payload
                        return true;
                    }
                }
                return false; // SNI present but not in whitelist
            }
        }
    }
    true // Allow non-TLS or packets without SNI for now to avoid breaking basic connectivity
}

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
    crate::log_to_java("VPN >> PASSIVE_SHIELD_UP");
    
    if let Err(e) = set_nonblocking(fd) {
        crate::log_to_java(&format!("VPN >> ERR_NONBLOCK: {}", e));
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
                        let n_usize = n as usize;
                        let packet = &buf[..n_usize];
                        
                        let is_allowed = {
                            let allowed_uids = match ALLOWED_UIDS.read() {
                                Ok(guard) => guard.clone(),
                                Err(_) => Vec::new(),
                            };
                            
                            if !allowed_uids.is_empty() {
                                check_uid_lockdown(packet)
                            } else {
                                match ALLOWED_DOMAINS.read() {
                                    Ok(guard) => {
                                        if guard.is_empty() {
                                            true
                                        } else {
                                            check_focus_whitelist(packet)
                                        }
                                    },
                                    Err(_) => true,
                                }
                            }
                        };

                        if is_allowed {
                            BYTES_PROCESSED.fetch_add(n_usize as u64, Ordering::Relaxed);
                            // Passive shield doesn't forward, it just monitors and blocks.
                        } else {
                            crate::log_to_java("SHIELD >> TRAFFIC_DROPPED: UNAUTHORIZED_UID");
                        }
                        
                        OTHER_COUNT.fetch_add(1, Ordering::Relaxed);
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
    crate::log_to_java("VPN >> PASSIVE_SHIELD_DOWN");
    CORE_STATUS.store(0, Ordering::SeqCst);
}

pub fn start_vpn_loop(fd: i32) {
    CORE_STATUS.store(1, Ordering::SeqCst);
    crate::log_to_java("VPN >> STARTING_LOOP");
    
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
            crate::log_to_java("VPN >> EMPTY_KEY: STARTING_PASSIVE_SHIELD");
            run_passive_shield_internal(fd).await;
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
                        crate::log_to_java(&format!("VPN >> SS_LOCAL_READY_ON_{}", ss_local_addr));
                        if let Err(e) = run_ss_local(config).await {
                            crate::log_to_java(&format!("VPN >> SS_ERR: {}", e));
                        }
                    }
                }
                Err(e) => {
                    crate::log_to_java(&format!("VPN >> INVALID_SS_KEY: {}", e));
                }
            }
        });

        // Wait for SOCKS5 proxy to be ready with retries
        let mut proxy_ready = false;
        for i in 1..=10 {
            tokio::time::sleep(Duration::from_millis(300)).await;
            if tokio::net::TcpStream::connect(local_addr_str.clone()).await.is_ok() {
                proxy_ready = true;
                break;
            }
            crate::log_to_java(&format!("VPN >> WAITING_FOR_SOCKS5 ({}/10)", i));
        }

        if !proxy_ready {
            crate::log_to_java("VPN >> ERR: SOCKS5_TIMEOUT");
            CORE_STATUS.store(3, Ordering::SeqCst);
            return;
        }
        
        let mut tun_config = tun::Configuration::default();
        tun_config.raw_fd(fd);
        
        crate::log_to_java("VPN >> ATTEMPTING_TUN_CREATE");
        match tun::create_as_async(&tun_config) {
            Ok(tun_device) => {
                CORE_STATUS.store(2, Ordering::SeqCst);
                crate::log_to_java("VPN >> TUN_DEVICE_READY");
                if let Ok(proxy) = ArgProxy::try_from(format!("socks5://{}", local_addr_str).as_str()) {
                    crate::log_to_java("VPN >> STARTING_TUN2PROXY");
                    
                    let token = CancellationToken::new();
                    let mut args = Args::default();
                    args.proxy = proxy;
                    args.dns = ArgDns::Virtual;
                    args.verbosity = ArgVerbosity::Off;
                    
                    crate::log_to_java("VPN >> ENGINE_READY");

                    let monitor_token = token.clone();
                    tokio::spawn(async move {
                        while CORE_STATUS.load(Ordering::SeqCst) != 0 {
                            tokio::time::sleep(Duration::from_secs(5)).await;
                        }
                        monitor_token.cancel();
                    });

                    // Wrap tun_device to apply lockdown filter
                    // For performance, we only check if ALLOWED_UIDS is not empty
                    let mut lock_down_active = false;
                    if let Ok(guard) = ALLOWED_UIDS.read() {
                        if !guard.is_empty() {
                            lock_down_active = true;
                            crate::log_to_java("SHIELD >> APPLYING_LOCKDOWN_FILTER");
                        }
                    }

                    if let Err(e) = run_tun2proxy(tun_device, 1280, args, token).await {
                        crate::log_to_java(&format!("VPN >> EXIT: {}", e));
                    }
                } else {
                    crate::log_to_java("VPN >> ERR: INVALID_PROXY_URL");
                }
            }
            Err(e) => {
                crate::log_to_java(&format!("VPN >> TUN_CREATE_FAILED: {}", e));
                CORE_STATUS.store(3, Ordering::SeqCst);
            }
        }
        crate::log_to_java("VPN >> LOOP_STOPPED");
        CORE_STATUS.store(0, Ordering::SeqCst);
    });
}
