use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::jstring;
use serde::{Serialize};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;
use tokio::net::TcpStream as AsyncTcpStream;
use std::sync::atomic::{AtomicU64, AtomicBool, Ordering};
use std::sync::RwLock;

// Shadowsocks and Tun2Proxy imports
use shadowsocks::config::ServerConfig;
use shadowsocks_service::config::{Config, ConfigType, LocalInstanceConfig, LocalConfig, ProtocolType, Mode};
use shadowsocks_service::local::run as run_ss_local;
use shadowsocks::config::ServerAddr;
use tun2proxy::{run as run_tun2proxy, ArgProxy, ArgDns, ArgVerbosity, CancellationToken};
use std::str::FromStr;

static TCP_COUNT: AtomicU64 = AtomicU64::new(0);
static UDP_COUNT: AtomicU64 = AtomicU64::new(0);
static OTHER_COUNT: AtomicU64 = AtomicU64::new(0);
static BYTES_PROCESSED: AtomicU64 = AtomicU64::new(0);
static STEALTH_MODE: AtomicBool = AtomicBool::new(false);

lazy_static::lazy_static! {
    static ref OUTLINE_KEY: RwLock<String> = RwLock::new(String::new());
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_toggleStealthMode(
    _env: JNIEnv,
    _class: JClass,
    enabled: bool,
) {
    STEALTH_MODE.store(enabled, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_setOutlineKey(
    mut env: JNIEnv,
    _class: JClass,
    key: JString,
) {
    let key_str: String = env.get_string(&key).expect("Invalid key").into();
    if let Ok(mut k) = OUTLINE_KEY.write() {
        *k = key_str;
    }
}

// Minimalist Token Bucket for Throttling (Bytes per second)
static BANDWIDTH_LIMIT: AtomicU64 = AtomicU64::new(0); // 0 = Unlimited

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_runVpnLoop(
    _env: JNIEnv,
    _class: JClass,
    fd: i32,
) {
    let rt = Runtime::new().unwrap();
    rt.block_on(async {
        // --- 1. Retrieve Key ---
        let key_guard = OUTLINE_KEY.read().unwrap();
        let key_str = key_guard.clone();
        drop(key_guard);

        if key_str.is_empty() {
            let mut buf = vec![0u8; 4096];
            loop {
                let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
                if n <= 0 { break; }
            }
            return;
        }

        // --- 2. Start Shadowsocks Local SOCKS5 Server ---
        let local_addr_str = "127.0.0.1:10808";
        let ss_key = key_str.clone();

        tokio::spawn(async move {
            match ServerConfig::from_url(&ss_key) {
                Ok(server_config) => {
                    let mut config = Config::new(ConfigType::Local);
                    
                    let mut local_config = LocalConfig::new(ProtocolType::Socks);
                    local_config.addr = Some(ServerAddr::from_str(local_addr_str).unwrap());
                    local_config.mode = Mode::TcpAndUdp;
                    
                    config.local.push(LocalInstanceConfig {
                        config: local_config,
                        acl: None,
                    });
                    config.server.push(server_config);
                    
                    if let Err(e) = run_ss_local(config).await {
                        eprintln!("Shadowsocks local failed: {}", e);
                    }
                },
                Err(e) => eprintln!("Invalid SS Key: {}", e),
            }
        });

        tokio::time::sleep(Duration::from_millis(500)).await;

        // --- 3. Setup tun2proxy ---
        let tun_device = unsafe { tun::platform::Device::from_raw_fd(fd) };
        let proxy = ArgProxy::from_str(&format!("socks5://{}", local_addr_str)).unwrap();
        let token = CancellationToken::new();

        if let Err(e) = run_tun2proxy(tun_device, 1500, proxy, ArgDns::Virtual, ArgVerbosity::None, token).await {
            eprintln!("tun2proxy failed: {}", e);
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_setBandwidthLimit(
    _env: JNIEnv,
    _class: JClass,
    limit_mbps: i32,
) {
    let bytes_per_sec = (limit_mbps as u64) * 1024 * 1024 / 8;
    BANDWIDTH_LIMIT.store(bytes_per_sec, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_getNativeBlockedCount(
    _env: JNIEnv,
    _class: JClass,
) -> i64 {
    (TCP_COUNT.load(Ordering::Relaxed) + 
     UDP_COUNT.load(Ordering::Relaxed) + 
     OTHER_COUNT.load(Ordering::Relaxed)) as i64
}

#[derive(Serialize)]
struct NetworkStats {
    ping: i64,
    jitter: i64,
    server: String,
}

#[derive(Serialize)]
struct DeviceInfo {
    ip: String,
    mac: String,
    status: String,
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_measureNetworkStats(
    mut env: JNIEnv,
    _class: JClass,
    target_ip: JString,
) -> jstring {
    let target_ip_str: String = match env.get_string(&target_ip) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string(r#"{"ping": -1, "jitter": 0, "server": "ERROR"}"#).unwrap().into_raw(),
    };

    let addr_str = if target_ip_str.contains(':') {
        target_ip_str.clone()
    } else {
        format!("{}:80", target_ip_str)
    };

    let addr: SocketAddr = addr_str.parse().unwrap_or_else(|_| "1.1.1.1:443".parse().unwrap());
    let timeout = Duration::from_millis(1500);
    let mut pings = Vec::with_capacity(3);

    for _ in 0..3 {
        let start = Instant::now();
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(_) => {
                pings.push(start.elapsed().as_millis() as i64);
            }
            Err(_) => {
                pings.push(-1);
            }
        }
    }

    let stats = if pings.iter().any(|&p| p == -1) {
        NetworkStats {
            ping: -1,
            jitter: 0,
            server: "UNREACHABLE".to_string(),
        }
    } else {
        let avg_ping: i64 = pings.iter().sum::<i64>() / pings.len() as i64;
        let max_ping = *pings.iter().max().unwrap_or(&0);
        let min_ping = *pings.iter().min().unwrap_or(&0);
        let jitter = max_ping - min_ping;

        NetworkStats {
            ping: avg_ping,
            jitter,
            server: target_ip_str,
        }
    };

    let json = serde_json::to_string(&stats).unwrap_or_else(|_| {
        r#"{"ping": -1, "jitter": 0, "server": "ERROR"}"#.to_string()
    });

    env.new_string(json)
        .expect("Failed to create Java String")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_scanSubnet(
    mut env: JNIEnv,
    _class: JClass,
    base_ip: JString,
) -> jstring {
    let base_ip_str: String = match env.get_string(&base_ip) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("[]").unwrap().into_raw(),
    };
    
    let rt = Runtime::new().unwrap();
    let devices = rt.block_on(async move {
        let mut tasks = Vec::with_capacity(254);

        for i in 1..255 {
            let ip = format!("{}{}", base_ip_str, i);
            tasks.push(tokio::spawn(async move {
                let ports = [80, 443, 5353, 62078];
                let mut found = false;
                for port in ports {
                    let addr_str = format!("{}:{}", ip, port);
                    if let Ok(addr) = addr_str.parse::<SocketAddr>() {
                        if let Ok(Ok(_)) = tokio::time::timeout(
                            Duration::from_millis(300),
                            AsyncTcpStream::connect(addr)
                        ).await {
                            found = true;
                            break;
                        }
                    }
                }

                if found {
                    return Some(DeviceInfo {
                        ip,
                        mac: "00:00:00:00:00:00".to_string(),
                        status: if i == 1 { "Gateway".to_string() } else { "Active".to_string() },
                    });
                }
                None
            }));
        }

        let mut results = Vec::new();
        for task in tasks {
            if let Ok(Some(device)) = task.await {
                results.push(device);
            }
        }
        results
    });

    let json = serde_json::to_string(&devices).unwrap_or_else(|_| "[]".to_string());
    env.new_string(json).unwrap().into_raw()
}
