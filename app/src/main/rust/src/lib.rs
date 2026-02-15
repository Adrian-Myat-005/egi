use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::jstring;
use serde::{Serialize};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;
use tokio::net::TcpStream as AsyncTcpStream;
use futures::future::select_all;

#[derive(Serialize)]
struct NetworkStats {
    ping: i64,
    jitter: i64,
    server: String,
}

#[derive(Serialize)]
struct DeviceInfo {
    ip: String,
    status: String,
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_measureNetworkStats(
    mut env: JNIEnv,
    _class: JClass,
    target_ip: JString,
) -> jstring {
    let target_ip_str: String = env.get_string(&target_ip).expect("Invalid target IP").into();
    let addr_str = if target_ip_str.contains(':') {
        target_ip_str.clone()
    } else {
        format!("{}:80", target_ip_str)
    };

    let addr: SocketAddr = addr_str.parse().unwrap_or_else(|_| "1.1.1.1:443".parse().unwrap());
    let timeout = Duration::from_millis(1500);
    let mut pings = Vec::new();

    for _ in 0..3 {
        let start = Instant::now();
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(_) => {
                let duration = start.elapsed().as_millis() as i64;
                pings.push(duration);
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
    let base_ip_str: String = env.get_string(&base_ip).expect("Invalid base IP").into();
    
    let rt = Runtime::new().unwrap();
    let devices = rt.block_on(async move {
        let mut tasks = Vec::new();

        for i in 1..255 {
            let ip = format!("{}{}", base_ip_str, i);
            tasks.push(tokio::spawn(async move {
                // Sonar Upgrade: Check multiple ports in parallel
                let ports = [80, 443, 5353, 62078];
                let mut connection_attempts = Vec::new();

                for port in ports {
                    let addr_str = format!("{}:{}", ip, port);
                    if let Ok(addr) = addr_str.parse::<SocketAddr>() {
                        connection_attempts.push(Box::pin(tokio::time::timeout(
                            Duration::from_millis(300),
                            AsyncTcpStream::connect(addr)
                        )));
                    }
                }

                if !connection_attempts.is_empty() {
                    // Wait for the first port to respond
                    while !connection_attempts.is_empty() {
                        let (res, _index, remaining) = select_all(connection_attempts).await;
                        if let Ok(Ok(_)) = res {
                            return Some(DeviceInfo {
                                ip,
                                status: if i == 1 { "Gateway".to_string() } else { "Device".to_string() },
                            });
                        }
                        connection_attempts = remaining;
                    }
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
