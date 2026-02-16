use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};
use serde::Serialize;

#[derive(Serialize)]
struct NetworkStats {
    ping: i64,
    jitter: i64,
    server: String,
}

pub fn measure_stats(target_ip_str: String) -> String {
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
            Ok(_) => pings.push(start.elapsed().as_millis() as i64),
            Err(_) => pings.push(-1),
        }
    }

    let stats = if pings.iter().any(|&p| p == -1) {
        NetworkStats { ping: -1, jitter: 0, server: "UNREACHABLE".to_string() }
    } else {
        let avg_ping: i64 = pings.iter().sum::<i64>() / pings.len() as i64;
        let jitter = pings.iter().max().unwrap_or(&0) - pings.iter().min().unwrap_or(&0);
        NetworkStats { ping: avg_ping, jitter, server: target_ip_str }
    };

    serde_json::to_string(&stats).unwrap_or_else(|_| r#"{"ping": -1}"#.to_string())
}
