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

pub async fn run_passive_shield_internal(fd: RawFd) {
    CORE_STATUS.store(2, Ordering::SeqCst);
    
    let async_fd = match AsyncFd::new(fd) {
        Ok(afd) => afd,
        Err(_) => {
            CORE_STATUS.store(3, Ordering::SeqCst);
            return;
        }
    };

    let mut buf = vec![0u8; 65536]; // 64KB buffer for high-speed throughput
    loop {
        match async_fd.readable().await {
            Ok(mut guard) => {
                match unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) } {
                    n if n > 0 => {
                        // Passive Shield is a pure black-hole for performance.
                        // Apps in 'Disallowed' list bypass this FD entirely (0ms overhead).
                        // Everything else coming here is dropped.
                        
                        BYTES_PROCESSED.fetch_add(n as u64, Ordering::Relaxed);
                        OTHER_COUNT.fetch_add(1, Ordering::Relaxed);
                        guard.clear_ready();
                    }
                    0 => break, // EOF
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

pub fn start_vpn_loop(fd: i32) {
    CORE_STATUS.store(1, Ordering::SeqCst);
    TOKIO_RT.block_on(async {
        let secure_key = match OUTLINE_KEY.read() {
            Ok(guard) => guard.clone(),
            Err(_) => {
                CORE_STATUS.store(3, Ordering::SeqCst);
                return;
            }
        };

        if secure_key.key.is_empty() {
            run_passive_shield_internal(fd).await;
            return;
        }

        let port = find_free_port().unwrap_or(10808);
        PROXY_PORT.store(port, Ordering::Relaxed);
        
        let local_addr_str = format!("127.0.0.1:{}", port);
        let ss_key = secure_key.key.clone();

        let ss_local_addr = local_addr_str.clone();
        tokio::spawn(async move {
            if let Ok(server_config) = ServerConfig::from_url(&ss_key) {
                let mut config = Config::new(ConfigType::Local);
                if let Ok(local_addr) = ServerAddr::from_str(&ss_local_addr) {
                    let mut local_config = LocalConfig::new(ProtocolType::Socks);
                    local_config.addr = Some(local_addr);
                    local_config.mode = Mode::TcpAndUdp;
                    config.local.push(LocalInstanceConfig { config: local_config, acl: None });
                    config.server.push(ServerInstanceConfig::with_server_config(server_config));
                    let _ = run_ss_local(config).await;
                }
            }
        });

        tokio::time::sleep(Duration::from_millis(500)).await;
        
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
                    let _ = run_tun2proxy(tun_device, 1400, args, token).await;
                }
            }
            Err(_) => {
                CORE_STATUS.store(3, Ordering::SeqCst);
            }
        }
        CORE_STATUS.store(0, Ordering::SeqCst);
    });
}
