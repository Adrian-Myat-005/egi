use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::jstring;
use serde::{Serialize};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;
use tokio::net::TcpStream as AsyncTcpStream;
use futures::future::select_all;

use std::sync::atomic::{AtomicU64, Ordering};

static TCP_COUNT: AtomicU64 = AtomicU64::new(0);
static UDP_COUNT: AtomicU64 = AtomicU64::new(0);
static OTHER_COUNT: AtomicU64 = AtomicU64::new(0);

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_runVpnLoop(
    _env: JNIEnv,
    _class: JClass,
    fd: i32,
) {
    let mut buf = [0u8; 65535]; // Max IP packet size
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
        if n <= 0 {
            break; 
        }

        if n >= 20 { // Minimum IPv4 header
            let version = buf[0] >> 4;
            let protocol = if version == 4 {
                buf[9] // IPv4 Protocol field
            } else if version == 6 && n >= 40 {
                buf[6] // IPv6 Next Header field
            } else {
                0
            };

            match protocol {
                6 => { TCP_COUNT.fetch_add(1, Ordering::Relaxed); }
                17 => { UDP_COUNT.fetch_add(1, Ordering::Relaxed); }
                _ => { OTHER_COUNT.fetch_add(1, Ordering::Relaxed); }
            }
        }
    }
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
                    let simulated_mac = format!("00:1A:2B:3C:4D:{:02X}", i);
                    return Some(DeviceInfo {
                        ip,
                        mac: simulated_mac,
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

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_kickDevice(
    mut env: JNIEnv,
    _class: JClass,
    _target_ip: JString,
    _target_mac: JString,
) -> bool {
    // Protocol: ARP Poisoning / Deauth Simulation
    true
}


