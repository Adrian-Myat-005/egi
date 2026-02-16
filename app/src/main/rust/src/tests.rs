#[cfg(test)]
mod tests {
    use crate::common::*;
    use std::sync::atomic::Ordering;

    #[test]
    fn test_core_stats_atomic() {
        BYTES_PROCESSED.store(0, Ordering::SeqCst);
        OTHER_COUNT.store(0, Ordering::SeqCst);
        
        BYTES_PROCESSED.fetch_add(1024, Ordering::SeqCst);
        OTHER_COUNT.fetch_add(1, Ordering::SeqCst);
        
        assert_eq!(BYTES_PROCESSED.load(Ordering::SeqCst), 1024);
        assert_eq!(OTHER_COUNT.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn test_secure_key_zeroize() {
        use zeroize::Zeroize;
        let mut key = SecureKey { key: "secret".to_string() };
        key.zeroize();
        assert!(key.key.is_empty());
    }
}
