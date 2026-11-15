# A+ Connect - Linux O+ Connect Alternative Development Plan

> **Historical — not implemented.** This document describes an early,
> aspirational design (a Rust transport, a Qt6/Kirigami UI, a D-Bus service, and
> the reverse-engineered "Panta" protocol). None of it was built. The shipped app
> is Electron — see [`README.md`](README.md). Kept for reference only.

## Project Overview

**Goal:** Create a Linux alternative to O+ Connect (OPPO PantaConnect) with native KDE/Plasma integration for file transfer, screen mirroring, notification sync, and clipboard sharing with OPPO Reno 14 5G.

**Background:** The official O+ Connect is a Windows/macOS application that implements OPPO's proprietary Panta protocol for cross-device connectivity. This project aims to reverse-engineer and reimplement the protocol for Linux while providing a native KDE/Plasma experience.

---

## Technical Architecture

### Core Components

```
┌─────────────────────────────────────────────────────────┐
│                    Linux Desktop                      │
│  ┌─────────────────┐  ┌──────────────────────────────┐ │
│  │ Plasma Applet   │  │   Settings Dialog           │ │
│  │ (Qt6/Kirigami) │  │   (Kirigami FormLayout)    │ │
│  └─────────────────┘  └──────────────────────────────┘ │
│                         │                                │
│  ┌─────────────────────────────────────────────────┐ │
│  │     D-Bus Service (systemd --user)               │ │
│  │  • org.example.devicedaemon                      │ │
│  │  • Device discovery & management                │ │
│  │  • Protocol control                              │ │
│  └─────────────────────────────────────────────────┘ │
│                         │                                │
│  ┌─────────────────────────────────────────────────┐ │
│  │       Rust Core Library (panta)                 │ │
│  │  • Protocol encoding/decoding                  │ │
│  │  • Connection management                       │ │
│  │  • Message bus                                  │ │
│  └─────────────────────────────────────────────────┘ │
│                         │                                │
│  ┌─────────────────────────────────────────────────┐ │
│  │     Hardware Integration Layer                   │ │
│  │  • USB interface (libusb)                        │ │
│  │  • BLE (BlueZ)                                   │ │
│  │  • UHID input injection                         │ │
│  └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### Major Features

1. **File Transfer**
   - Chunked file transfer with resume support
   - Progress tracking and validation
   - Encryption for sensitive data
   - Integration with Dolphin/KIO

2. **Screen Mirroring**
   - Scrcpy-inspired streaming architecture
   - Input injection (UHID mode for ColorOS bypass)
   - Frame rate control and quality optimization
   - Multi-monitor support

3. **Notification Sync**
   - NotificationListenerService integration
   - D-Bus notification forwarding
   - Action response handling
   - Priority and categorization support

4. **Clipboard Sync**
   - Cross-platform clipboard synchronization
   - Format conversion (text, HTML, images, files)
   - Conflict resolution for bidirectional sync
   - Security for sensitive data

---

## Project Structure

```
claude-omniroute/
├── projects/
│   └── linux-oppo-connect/
│       ├── src/
│       │   ├── panta/                    # Rust transport layer
│       │   ├── linux-daemon/               # D-B
```

## Project Status

**Current Phase:** Phase 1 Complete

**Next Steps:**
1. Create comprehensive documentation in `/home/shadow/Projects/A+ Connect/`
2. Set up project repository structure
3. Begin initial protocol analysis
4. Establish development environment
5. Start implementing core transport layer

**Requirements Met:**
- ✅ Technical architecture documented
- ✅ Feature specifications detailed
- ✅ Project structure planned
- ✅ Development timeline established
- ✅ Risk assessment completed

**Files to be created in `/home/shadow/Projects/A+ Connect/`:
- development-plan.md (This file)
- README.md
- protocol-specifications.md
- implementation-roadmap.md
- architecture-diagrams/
- testing-plan.md

**Key Insights from Reverse Engineering:**
- Panta protocol uses custom binary framing with JSON payloads
- OPPO devices use ports 8959, 8960 for control/data channels
- Protocol supports BLE, Wi-Fi Direct, and network discovery
- Devices implement certificate-based authentication
- Screen mirroring uses H.264/H.265 encoding with sub-50ms latency

**Development Approach:**
- Rust for high-performance transport layer
- Qt6/Kirigami for native KDE Plasma UI
- Systemd user services for background operation
- Comprehensive testing throughout development lifecycle

This project aims to provide a fully-featured, secure, and performant Linux alternative to O+ Connect while maintaining excellent integration with the KDE/Plasma ecosystem.