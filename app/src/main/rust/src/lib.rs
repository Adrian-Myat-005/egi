use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::jstring;
use serde::Serialize;
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

#[derive(Serialize)]
struct NetworkStats {
    ping: i64,
    jitter: i64,
    status: String,
}

#[no_mangle]
pub extern "system" fn Java_com_example_egi_EgiNetwork_measureNetworkStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let addr: SocketAddr = "1.1.1.1:443".parse().unwrap();
    let timeout = Duration::from_millis(2000);
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
            status: "unstable".to_string(),
        }
    } else {
        let avg_ping: i64 = pings.iter().sum::<i64>() / pings.len() as i64;
        let max_ping = *pings.iter().max().unwrap_or(&0);
        let min_ping = *pings.iter().min().unwrap_or(&0);
        let jitter = max_ping - min_ping;

        NetworkStats {
            ping: avg_ping,
            jitter,
            status: "secure".to_string(),
        }
    };

    let json = serde_json::to_string(&stats).unwrap_or_else(|_| {
        r#"{"ping": -1, "jitter": 0, "status": "error"}"#.to_string()
    });

    env.new_string(json)
        .expect("Failed to create Java String")
        .into_raw()
}
