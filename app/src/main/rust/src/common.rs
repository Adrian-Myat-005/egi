use std::sync::atomic::{AtomicU64, AtomicBool, AtomicU16, AtomicU8};
use std::sync::RwLock;
use tokio::runtime::Runtime;
use zeroize::{Zeroize, ZeroizeOnDrop};

pub static TCP_COUNT: AtomicU64 = AtomicU64::new(0);
pub static UDP_COUNT: AtomicU64 = AtomicU64::new(0);
pub static OTHER_COUNT: AtomicU64 = AtomicU64::new(0);
pub static BYTES_PROCESSED: AtomicU64 = AtomicU64::new(0);
pub static STEALTH_MODE: AtomicBool = AtomicBool::new(false);
pub static PROXY_PORT: AtomicU16 = AtomicU16::new(10808);

// Health Status: 0=STOPPED, 1=STARTING, 2=RUNNING, 3=ERROR
pub static CORE_STATUS: AtomicU8 = AtomicU8::new(0);

#[derive(Zeroize, ZeroizeOnDrop, Default, Clone)]
pub struct SecureKey {
    pub key: String,
}

lazy_static::lazy_static! {
    pub static ref OUTLINE_KEY: RwLock<SecureKey> = RwLock::new(SecureKey::default());
    pub static ref ALLOWED_DOMAINS: RwLock<Vec<String>> = RwLock::new(Vec::new());
    pub static ref ALLOWED_UIDS: RwLock<Vec<u32>> = RwLock::new(Vec::new());
    pub static ref TOKIO_RT: Runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("Failed to create Tokio runtime");
}

pub static BANDWIDTH_LIMIT: AtomicU64 = AtomicU64::new(0);
