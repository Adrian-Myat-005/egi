# Igy Shield (EGI) - System Architecture Overview

This document provides a technical overview of the **Igy Shield** ecosystem, a high-performance, security-focused Android VPN and network analysis suite.

## 1. High-Level Architecture
Igy Shield is built on a three-tier architecture designed for maximum performance, security, and cross-platform flexibility:

1.  **Frontend (Android/Kotlin):** Manages the UI, Android system services (`VpnService`), and high-level logic.
2.  **Core Engine (Rust/Native):** Handles low-level packet interception, proxy tunneling, and network analysis.
3.  **Backend (Node.js/TypeScript):** Manages user accounts, authentication (JWT), and dynamic VPN configuration.

---

## 2. Component Breakdown

### A. Android Application (Kotlin & Jetpack Compose)
*   **UI/UX:** A unique "Terminal Dashboard" aesthetic. Minimalist cream-colored background (`#FDF5E6`), tactile white buttons with 2dp elevation, and subtle ripple/pulse animations.
*   **VPN Management:** Implements `VpnService` to establish a TUN interface.
*   **Security:** Uses `AndroidKeyStore` (via `SecurityUtils.kt`) to encrypt sensitive data like VPN keys and credentials at rest.
*   **Network Intelligence:** Monitors Wi-Fi SSIDs (`WifiReceiver`) and pings gateways to provide real-time latency/jitter metrics on the dashboard.

### B. Native Core (Rust Engine)
The core engine resides in `app/src/main/rust` and is compiled into `libigy_core.so`.
*   **Tunneling:** Utilizes `tun2proxy` to bridge the L3 TUN device to L7 proxy protocols.
*   **Encryption:** Embeds a full `shadowsocks-service` (ss-local) instance using AEAD ciphers for secure traffic egress.
*   **Communication:** Communicates with Kotlin via a robust **JNI (Java Native Interface)** layer with cached global references to prevent background thread crashes.
*   **Modes of Operation:**
    *   **VPN Shield (Global):** Full device tunneling.
    *   **Focus Mode (Allowed Apps):** Intercepts only specific app traffic.
    *   **Bypass Mode (Disallowed Apps):** Intercepts everything *except* specified apps.
    *   **Passive Shield:** Lightweight SNI-based domain blocking without proxying, used when no valid key is present.

### C. Backend API (Node.js & TypeScript)
*   **Stack:** Express.js, Sequelize ORM (PostgreSQL/SQLite), and JWT for authentication.
*   **Endpoints:**
    *   `POST /api/auth/register` & `/api/auth/login`: User management.
    *   `GET /api/vpn/config`: Serves dynamic Shadowsocks keys based on user subscription level and selected region.
    *   `GET /api/vpn/test-key`: Provides temporary access for guest users.

---

## 3. Communication Protocols
*   **App <-> Backend:** HTTPS/REST with Bearer Token authentication.
*   **App <-> Core Engine:** JNI calls (Rust `extern "system"` functions).
*   **Core Engine <-> VPN Server:** Shadowsocks (Encrypted TCP/UDP tunnel).
*   **In-App Logging:** Native Rust logs are piped back to the Kotlin `TrafficEvent` bus and displayed in the "Console Logs" terminal view.

---

## 4. Key Workflows

### The "Engage Shield" Flow
1.  **UI:** User clicks `[ ENGAGE_SHIELD ]`.
2.  **Kotlin:** Validates configuration, requests VPN permission, and starts `IgyVpnService`.
3.  **Service:** Establishes `ParcelFileDescriptor` for the TUN interface.
4.  **JNI:** Passes the File Descriptor (FD) and Shadowsocks key to the Rust `runVpnLoop`.
5.  **Rust:** Sets FD to non-blocking, starts a local SOCKS5 proxy (Shadowsocks), and initializes `tun2proxy` to handle all incoming packets.

### Threat Deflection
*   The Rust engine performs L4-L7 inspection.
*   Blocked packets (based on `ALLOWED_DOMAINS`) are counted and reported via JNI.
*   The dashboard updates the "THREATS_DEFLECTED" counter in real-time.

---

## 5. Technical Requirements
*   **Android:** API 24+ (Android 7.0 to Android 14+).
*   **Rust:** 2021 Edition, Tokio Runtime (Multi-threaded).
*   **Permissions:** `BIND_VPN_SERVICE`, `POST_NOTIFICATIONS`, `ACCESS_FINE_LOCATION` (for Wi-Fi Radar).

---
*Created for Igy Shield (EGI) Architecture Review.*
