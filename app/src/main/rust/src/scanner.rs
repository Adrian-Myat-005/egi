use std::net::SocketAddr;
use std::time::Duration;
use tokio::net::TcpStream as AsyncTcpStream;
use serde::Serialize;
use crate::common::TOKIO_RT;

#[derive(Serialize)]
struct DeviceInfo {
    #[serde(rename = "i")]
    ip: String,
    #[serde(rename = "m")]
    mac: String,
    #[serde(rename = "s")]
    status: String,
}

pub fn scan_subnet(base_ip_str: String) -> String {
    let devices = TOKIO_RT.block_on(async move {
        let mut tasks = Vec::with_capacity(254);
        for i in 1..255 {
            let mut prefix = base_ip_str.clone();
            if !prefix.ends_with('.') {
                prefix.push('.');
            }
            let ip = format!("{}{}", prefix, i);
            tasks.push(tokio::spawn(async move {
                // TCP Scan
                let ports = [80, 443, 62078];
                for port in ports {
                    let addr_str = format!("{}:{}", ip, port);
                    if let Ok(addr) = addr_str.parse::<SocketAddr>() {
                        if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(200), AsyncTcpStream::connect(addr)).await {
                            return Some(DeviceInfo {
                                ip,
                                mac: "00:00:00:00:00:00".to_string(),
                                status: if i == 1 { "Gateway".to_string() } else { "Active".to_string() },
                            });
                        }
                    }
                }

                // UDP mDNS Scan (Port 5353)
                let mdns_addr = format!("{}:5353", ip);
                if let Ok(addr) = mdns_addr.parse::<SocketAddr>() {
                    let socket = tokio::net::UdpSocket::bind("0.0.0.0:0").await.ok();
                    if let Some(s) = socket {
                        let _ = s.send_to(b"\x00\x00\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\x09_services\x07_dns-sd\x04_udp\x05local\x00\x00\x0c\x00\x01", addr).await;
                        let mut buf = [0u8; 12];
                        if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(150), s.recv_from(&mut buf)).await {
                            return Some(DeviceInfo {
                                ip,
                                mac: "00:00:00:00:00:00".to_string(),
                                status: "mDNS Device".to_string(),
                            });
                        }
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

    serde_json::to_string(&devices).unwrap_or_else(|_| "[]".to_string())
}
