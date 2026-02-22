# Igy Shield (EGI) - System Architecture Overview

This document provides a technical overview of the **Igy Shield** ecosystem, a high-performance, security-focused Android VPN suite.

## 1. High-Level Architecture
Igy Shield is built on a two-tier architecture designed for maximum performance and security:

1.  **Frontend (Android/Kotlin):** Manages the UI, Android system services (`VpnService`), and high-level logic (e.g., Auto-Start triggers).
2.  **Core Engine (Rust/Native):** Handles low-level packet interception, UID-based filtering (Lockdown), and proxy tunneling.
3.  **Backend (Node.js/TypeScript):** Manages user accounts, authentication (JWT), and dynamic VPN configuration.

---

## 2. Component Breakdown

### A. Android Application (Kotlin & Jetpack Compose)
*   **UI/UX:** A unique "Terminal Dashboard" aesthetic. Minimalist cream-colored background (`#FDF5E6`), tactile white buttons, and subtle ripple/pulse animations.
*   **VPN Management:** Implements `VpnService` to establish a TUN interface.
*   **Auto-Trigger System:** Uses the **Usage Events API** via `AutoTriggerService` for **instant**, battery-efficient VPN activation when target apps are opened.
*   **Ghost Mode:** Implements a background "silent" monitoring state that keeps the status bar clean, only showing notifications when the VPN is actively waking up or protecting an app.
*   **Security:** Uses `AndroidKeyStore` (via `SecurityUtils.kt`) to encrypt sensitive data like VPN keys at rest.

### B. Native Core (Rust Engine)
The core engine resides in `app/src/main/rust` and is compiled into `libigy_core.so`.
*   **True Lockdown Filter:** Implements a custom `FilteredTun` wrapper. For every packet, it parses `/proc/net/tcp` and `/proc/net/udp` to identify the sender's UID. unauthorized traffic is **dropped immediately** at the kernel level.
*   **Tunneling:** Utilizes `tun2proxy` to bridge the L3 TUN device to L7 proxy protocols.
*   **Encryption:** Embeds a full `shadowsocks-service` (ss-local) instance using AEAD ciphers for secure traffic egress.
*   **I/O Performance:** Uses non-blocking file descriptors with Tokio's `AsyncFd` for maximum throughput.

---

## 3. The Tri-State Operational Modes
Igy Shield orchestrates network traffic through three distinct, user-selectable states:

| Mode | Behavior | Technology |
| :--- | :--- | :--- |
| **[VPN GLOBAL]** | Full-device encrypted tunnel. | Global Routing + `runVpnLoop` |
| **[VPN FOCUS]** | **True Lockdown**: ONLY selected apps get internet. | UID Filtering + `FilteredTun` + `runVpnLoop` |
| **[TURBO ACCELERATOR]** | Speed optimization by blocking background data. | UID Exclusion + `runPassiveShield` |

*   **VPN FOCUS (Lockdown):** Unlike standard split-tunneling, this mode physically cuts off internet access for every app *except* the selected focus target, ensuring 100% bandwidth and zero background leaks.

---

## 4. Communication Protocols
*   **App <-> Backend:** HTTPS/REST with Bearer Token authentication.
*   **App <-> Core Engine:** JNI calls using `JLongArray` for efficient UID synchronization.
*   **Core Engine <-> VPN Server:** Shadowsocks (Encrypted TCP/UDP tunnel).
*   **In-App Logging:** Native Rust logs (e.g., `SHIELD >> LOCKDOWN_FILTER: ENABLED`) are piped back to the Kotlin `TrafficEvent` bus for the Console view.

---

## 5. Technical Requirements
*   **Android:** API 24+ (Android 7.0 to Android 14+).
*   **Rust:** 2021 Edition, Tokio Runtime.
*   **Permissions:** `BIND_VPN_SERVICE`, `POST_NOTIFICATIONS`, `PACKAGE_USAGE_STATS` (for Auto-Trigger).

---
*Updated Feb 21, 2026 - Igy Shield (EGI) Architecture.*
