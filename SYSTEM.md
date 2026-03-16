# IGY SHIELD - System Architecture Overview

This document provides a technical overview of the **IGY SHIELD** ecosystem, a high-performance, security-focused Android VPN suite.

## 1. High-Level Architecture
IGY SHIELD is built on a two-tier architecture designed for maximum performance and security:

1.  **Frontend (Android/Kotlin):** Manages the UI, Android system services (`VpnService`), and high-level logic.
2.  **Core Engine (Rust/Native):** Handles low-level packet interception, UID-based filtering, and proxy tunneling.
3.  **Backend (Node.js/TypeScript):** Manages user accounts, authentication (JWT), and dynamic VPN configuration.

---

## 2. Core Protection Modes
IGY SHIELD orchestrates network traffic through three distinct, user-selectable states:

| Mode | Behavior | Technology |
| :--- | :--- | :--- |
| **[VPN]** | **Full Privacy**: Encrypts ALL device traffic through a secure tunnel. | `runVpnLoop` |
| **[VPN FOCUS]** | **Target Privacy**: Encrypts ONLY specific apps you pick. Rest of phone is untouched. | `runVpnLoop` + Allowed Apps |
| **[NORMAL FOCUS]** | **Speed Boost**: ACCELERATE your VIP apps by blocking background data for others. | `runPassiveShield` |

---

## 3. The Intelligent Watchdog System
The **Watchdog** is the core "Background Brain" of IGY SHIELD, ensuring protection is always stable.

*   **Core Health Monitor:** Checks the Rust engine every 5 seconds. If a stall is detected, it automatically re-executes the handshake.
*   **Auto-Start Trigger:** Detects when your "VIP" apps are opened and automatically ignites the shield with a 500ms grace period to prevent jitter.
*   **Safe-Step MTU:** Dynamically scales MTU from 1280 (Safe) to 1420 (High-Speed) based on network stability.
*   **Smart Tile Service:** One-tap control via the Android Quick Settings "IGY SHIELD" tile.

---

## 4. Technical Component Breakdown

### A. Android Application (Kotlin & Jetpack Compose)
*   **UI/UX:** Unique **Blue Gradient** aesthetic with a subtle **Network Pattern** background. 
*   **Glassmorphism Connect Button:** A realistic, tactile circular button with an integrated toggle switch and "CONNECT/DISCONNECT" labels.
*   **Dynamic Island Popup:** Provides visual feedback in the status bar for connection and disconnection events.
*   **Local-First Key Strategy:** Uses cached keys for instant connection while refreshing keys in the background.

### B. Native Core (Rust Engine)
The core engine resides in `app/src/main/rust` and is compiled into `libigy_core.so`.
*   **Network Bridging:** Utilizes `tun2proxy` to bridge the L3 TUN device to L7 proxy protocols.
*   **Shadowsocks Integration:** Embeds a full `shadowsocks-service` instance using AEAD ciphers for secure traffic egress.
*   **Health Handshake:** Rust monitor performs end-to-end SOCKS5 handshakes to verify the proxy is alive.

---

## 5. Security & Privacy
*   **Encrypted Storage:** Uses `AndroidKeyStore` to encrypt sensitive data like VPN keys at rest.
*   **Zero Logs:** No traffic data is ever sent to the backend; all filtering and interception happen locally on the device.

---

## 6. Technical Requirements
*   **Android:** API 24+ (Android 7.0 to Android 14+).
*   **Rust:** 2021 Edition, Tokio Runtime.
*   **Permissions:** `BIND_VPN_SERVICE`, `POST_NOTIFICATIONS`, `BIND_QUICK_SETTINGS_TILE`.

---
*Updated March 16, 2026 - IGY SHIELD Architecture.*
