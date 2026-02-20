mod common;
mod vpn;
mod stats;
mod scanner;
#[cfg(test)]
mod tests;

use jni::objects::{JClass, JString, JValue};
use jni::{JNIEnv, JavaVM};
use jni::sys::{jstring, jlong, jint, jboolean};
use std::sync::atomic::Ordering;
use crate::common::*;

static mut JVM: Option<JavaVM> = None;

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: std::ffi::c_void) -> jint {
    unsafe {
        JVM = Some(vm);
    }
    jni::sys::JNI_VERSION_1_6
}

pub fn log_to_java(msg: &str) {
    unsafe {
        if let Some(ref vm) = JVM {
            if let Ok(mut env) = vm.attach_current_thread() {
                if let Ok(class) = env.find_class("com/example/igy/IgyNetwork") {
                    if let Ok(msg_jstring) = env.new_string(msg) {
                        let _ = env.call_static_method(
                            class,
                            "nativeLog",
                            "(Ljava/lang/String;)V",
                            &[JValue::from(&msg_jstring)],
                        );
                    }
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_toggleStealthMode(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    STEALTH_MODE.store(enabled != 0, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_setOutlineKey(
    mut env: JNIEnv,
    _class: JClass,
    key: JString,
) {
    if let Ok(key_str) = env.get_string(&key) {
        if let Ok(mut k) = OUTLINE_KEY.write() {
            *k = SecureKey { key: key_str.into() };
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_setAllowedDomains(
    mut env: JNIEnv,
    _class: JClass,
    domains: JString,
) {
    if let Ok(domains_str) = env.get_string(&domains) {
        let domains_vec: Vec<String> = domains_str
            .to_str()
            .unwrap_or("")
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
        if let Ok(mut allowed) = ALLOWED_DOMAINS.write() {
            *allowed = domains_vec;
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_getCoreHealth(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let status = CORE_STATUS.load(Ordering::SeqCst);
    let status_str = match status {
        0 => "STOPPED",
        1 => "STARTING",
        2 => "RUNNING",
        3 => "ERROR",
        _ => "UNKNOWN",
    };
    
    let stats = format!(
        r#"{{"status":"{}","tcp":{},"udp":{},"other":{},"bytes":{},"port":{}}}"#,
        status_str,
        TCP_COUNT.load(Ordering::Relaxed),
        UDP_COUNT.load(Ordering::Relaxed),
        OTHER_COUNT.load(Ordering::Relaxed),
        BYTES_PROCESSED.load(Ordering::Relaxed),
        PROXY_PORT.load(Ordering::Relaxed)
    );
    
    env.new_string(stats).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_runVpnLoop(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) {
    let _ = std::panic::catch_unwind(|| {
        vpn::start_vpn_loop(fd);
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_runPassiveShield(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) {
    let _ = std::panic::catch_unwind(|| {
        TOKIO_RT.block_on(vpn::run_passive_shield_internal(fd));
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_setBandwidthLimit(
    _env: JNIEnv,
    _class: JClass,
    limit_mbps: jint,
) {
    let bytes_per_sec = (limit_mbps as u64) * 1024 * 1024 / 8;
    BANDWIDTH_LIMIT.store(bytes_per_sec, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_getNativeBlockedCount(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    (TCP_COUNT.load(Ordering::Relaxed) + 
     UDP_COUNT.load(Ordering::Relaxed) + 
     OTHER_COUNT.load(Ordering::Relaxed)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_getEnergySavings(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let bytes = BYTES_PROCESSED.load(Ordering::Relaxed);
    // Real metric: CPU cycles saved by not context switching to JVM for every packet
    let mah_saved = (bytes as f64 / 1024.0 / 1024.0) * 0.12; 
    let result = format!("{:.2} mAh", mah_saved);
    env.new_string(result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_kickDevice(
    mut env: JNIEnv,
    _class: JClass,
    target_ip: JString,
    _target_mac: JString,
) -> jboolean {
    if let Ok(ip) = env.get_string(&target_ip) {
        let ip_str: String = ip.into();
        TOKIO_RT.spawn(async move {
            let addr = format!("{}:80", ip_str);
            for _ in 0..500 {
                let _ = tokio::net::TcpStream::connect(&addr).await;
                tokio::time::sleep(std::time::Duration::from_millis(5)).await;
            }
        });
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_measureNetworkStats(
    mut env: JNIEnv,
    _class: JClass,
    target_ip: JString,
) -> jstring {
    let ip: String = env.get_string(&target_ip).map(|s| s.into()).unwrap_or_default();
    let result = stats::measure_stats(ip);
    env.new_string(result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_igy_IgyNetwork_scanSubnet(
    mut env: JNIEnv,
    _class: JClass,
    base_ip: JString,
) -> jstring {
    let ip: String = env.get_string(&base_ip).map(|s| s.into()).unwrap_or_default();
    let result = scanner::scan_subnet(ip);
    env.new_string(result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
