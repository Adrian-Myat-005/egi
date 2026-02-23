# Igy Shield (EGI) - System Architecture Overview

This document provides a technical overview of the **Igy Shield** ecosystem, a high-performance, security-focused Android VPN suite.

## 1. High-Level Architecture
Igy Shield is built on a two-tier architecture designed for maximum performance and security:

1.  **Frontend (Android/Kotlin):** Manages the UI, Android system services (`VpnService`), and high-level logic (e.g., 24/7 Guard).
2.  **Core Engine (Rust/Native):** Handles low-level packet interception, UID-based filtering (Lockdown), and proxy tunneling.
3.  **Backend (Node.js/TypeScript):** Manages user accounts, authentication (JWT), and dynamic VPN configuration.

---

## 2. The Four Pillars of Protection
Igy Shield orchestrates network traffic through four distinct, user-selectable states:

| Mode | Pillar | Behavior | Technology |
| :--- | :--- | :--- | :--- |
| **[NORMAL FOCUS]** | **Pillar 1** | **Speed Boost**: Blocks background data for one chosen app to maximize bandwidth. | `runPassiveShield` |
| **[GLOBAL VPN]** | **Pillar 2** | **Full Privacy**: Encrypts all device traffic through a secure tunnel. | `runVpnLoop` |
| **[VPN FOCUS]** | **Pillar 3** | **Target Privacy**: Encrypts ONLY one specific app. Rest of phone is untouched. | `runVpnLoop` + UID Filtering |
| **[SMART FILTER]** | **Pillar 4** | **Auto-Start List**: Protects a pre-selected list of apps automatically. | `runVpnLoop` + App Routing |

---

## 3. The 24/7 Master Guard System
The Master Guard is the core "Background Brain" of Igy Shield, ensuring protection is always ready but never wasteful.

### A. Quick Settings Integration
*   **Tile Tap (Single Click):** Instantly toggles the **Master Guard** ON/OFF. By default, this activates **Pillar 4 (Smart Filter)** for your chosen target apps.
*   **Tile Long-Press (Hold 1s):** Opens the **Command Center Popup**. This split-window menu allows instant switching between Pillar 1 (Normal Focus), Pillar 2 (Global), or Pillar 3 (VPN Focus) without opening the main app.

### B. Intelligent Battery Management (1-Hour Sleep)
*   **Screen OFF Logic:** When the screen is turned off, a 1-hour timer starts. If the screen remains off for > 1 hour, the VPN tunnel is closed to eliminate battery drain.
*   **Auto-Wake Logic:** The moment the user turns the screen back ON, the Guard immediately re-establishes the VPN tunnel.
*   **Sticky Service:** The `IgyVpnService` remains in a "Sticky" state with a background notification ("GUARD: SLEEPING") to ensure Android never kills the protection listener.

---

## 4. Technical Component Breakdown

### A. Android Application (Kotlin & Jetpack Compose)
*   **UI/UX:** Unique "Terminal Dashboard" aesthetic. Minimalist cream-colored background (`#FDF5E6`), tactile white buttons, and interactive pulse animations.
*   **Command Center Hub:** A custom translucent `SelectionHubActivity` designed to provide instant Pillar selection directly from the notification shade.
*   **Atomic Tunneling:** Implements thread-safe "Reconnect" locks to prevent race conditions during the Guard's auto-wake sequence.

### B. Native Core (Rust Engine)
The core engine resides in `app/src/main/rust` and is compiled into `libigy_core.so`.
*   **True Lockdown Filter:** For every packet, it parses `/proc/net/tcp` and `/proc/net/udp` to identify the sender's UID. Unauthorized traffic is **dropped immediately** at the kernel level.
*   **Tunneling:** Utilizes `tun2proxy` to bridge the L3 TUN device to L7 proxy protocols (Shadowsocks).
*   **Encryption:** Embeds a full `shadowsocks-service` instance using AEAD ciphers for secure traffic egress.

---

## 5. Security & Privacy
*   **Encrypted Storage:** Uses `AndroidKeyStore` (via `SecurityUtils.kt`) to encrypt sensitive data like VPN keys at rest.
*   **Zero Logs (External):** No traffic data is ever sent to the backend; all filtering and interception happen locally on the device via the Rust Core.

---

## 6. Technical Requirements
*   **Android:** API 24+ (Android 7.0 to Android 14+).
*   **Rust:** 2021 Edition, Tokio Runtime.
*   **Permissions:** `BIND_VPN_SERVICE`, `POST_NOTIFICATIONS`, `BIND_QUICK_SETTINGS_TILE`.

---
*Updated Feb 23, 2026 - Igy Shield (EGI) Architecture.*
