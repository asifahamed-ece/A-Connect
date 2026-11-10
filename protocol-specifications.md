# A+ Connect - Panta Protocol Specifications

## Protocol Overview

The Panta protocol (also known as O+ Connect, Multi-Screen Connect, or OPPO PantaConnect) is a custom binary framing protocol developed by OPPO for cross-device connectivity. This document details the protocol structure based on reverse engineering of OPPO binaries.

## Frame Structure

### PlainControl Frame

The core unit of communication in the Panta protocol is the PlainControl frame:

```
struct PlainControl {
    uint8_t server;      // 1 if frame originates from server, 0 from client
    uint8_t hello;       // 1 if this is a hello message, 0 otherwise
    uint8_t agent[4];    // 4-byte agent identifier
    uint32_t protobuf_len; // Length of Protobuf payload
    uint8_t protobuf_data[protobuf_len]; // Protobuf-encoded message
};
```

### Frame Fields

| Field | Size | Description |
|-------|------|-------------|
| `server` | 1 byte | Direction flag (server=1, client=0) |
| `hello` | 1 byte | Hello message indicator |
| `agent` | 4 bytes | Agent identifier (e.g., "SC1.0") |
| `protobuf_len` | 4 bytes | Length of Protobuf payload |
| `protobuf_data` | variable | Protobuf-encoded message |

## Agent Identifiers

The `agent` field contains 4-byte identifiers for different OPPO components:

- `"SC1.0"` - Screen casting service
- `"ONET"` - OPPO Network service
- `"PANT"` - Panta protocol service
- `"DEV"` - Device management service

## Protobuf Message Structure

The protocol uses Protocol Buffers for structured messaging. Based on analysis of OPPO binaries, the following message types are supported:

### Common Message Headers

```proto
message Header {
    uint32 message_id = 1;
    uint32 sequence_id = 2;
    uint32 timestamp = 3;
    string device_id = 4;
    string session_id = 5;
}
```

### Key Message Types

#### 1. Device Discovery
```proto
message DeviceDiscovery {
    repeated DeviceInfo devices = 1;
}
```

#### 2. Authentication
```proto
message AuthRequest {
    string device_id = 1;
    bytes certificate = 2;
    uint32 timestamp = 3;
}

message AuthResponse {
    bool success = 1;
    string session_id = 2;
    repeated string trusted_devices = 3;
}
```

#### 3. File Transfer
```proto
message FileTransfer {
    string file_id = 1;
    string filename = 2;
    uint64 filesize = 3;
    uint32 chunk_index = 4;
    bytes chunk_data = 5;
    uint32 total_chunks = 6;
}
```

#### 4. Screen Mirroring
```proto
message ScreenCapture {
    uint32 width = 1;
    uint32 height = 2;
    uint32 framerate = 3;
    bytes frame_data = 4;
    uint32 sequence_id = 5;
}

message InputEvent {
    uint32 event_type = 1; // 1=mouse, 2=keyboard, 3=touch
    uint32 x = 2;
    uint32 y = 3;
    uint32 keycode = 4;
    uint32 flags = 5;
}
```

## Network Communication

### Port Usage

- **Port 8959**: Control channel (authentication, session management)
- **Port 8960**: Data channel (file transfer, screen mirroring)
- **Default ports** can be dynamically negotiated during pairing

### Connection Flow

1. **Device Discovery**
   - BLE advertising with service UUID `0x8881`
   - mDNS/DNS-SD service discovery
   - UDP broadcast for local network discovery

2. **Authentication**
   - Certificate exchange during initial connection
   - SHA-256 verification of device certificates
   - Session establishment with mutual authentication

3. **Channel Establishment**
   - TCP connection establishment on control port
   - TLS encryption for secure communication
   - Port forwarding for data channel

### Message Routing

```
Client (Phone)                              Server (Desktop)
   |                                              |
   | 1. Discovery Request                         |
   |----------------------------------------->     |
   |                                             |
   | 2. Device List Response                     |
   |<-----------------------------------------     |
   |                                             |
   | 3. Authentication Request                   |
   |----------------------------------------->     |
   |                                             |
   | 4. Certificate Exchange                     |
   |<-----------------------------------------     |
   |                                             |
   | 5. Session Establishment                    |
   |----------------------------------------->     |
   |                                             |
   | 6. Data Channel Setup                       |
   |<-----------------------------------------     |
   |                                             |
   | ... Continue with file transfer,          |
   | ... screen sharing, notifications, etc.    |
```

## Security Mechanisms

### Encryption

1. **Certificate-Based Authentication**
   - Self-signed X.509 certificates
   - Certificate chain verification
   - Certificate revocation checking

2. **Transport Layer Security**
   - TLS 1.2/1.3 for all control channel communication
   - Forward-secrecy cipher suites
   - Certificate pinning for critical services

3. **Application-Layer Encryption**
   - AES-256-GCM for sensitive data
   - Per-session keys derived from ECDH
   - MAC authentication for all messages

### Access Control

1. **Device Pairing**
   - Manual pairing with verification codes
   - Automatic pairing via QR codes
   - Trust-based pairing for known devices

2. **Permission Management**
   - Per-capability access control
   - User-granted permissions for sensitive operations
   - Role-based access for administrative functions

## Message Types and Format

### Control Messages

| Message Type | Direction | Description |
|--------------|-----------|-------------|
| `DEVICE_DISCOVERY` | Client→Server | Request list of nearby devices |
| `DEVICE_INFO` | Server→Client | Information about discovered devices |
| `AUTH_REQUEST` | Client→Server | Authenticate with device |
| `AUTH_RESPONSE` | Server→Client | Authentication result |
| `SESSION_ESTABLISH` | Both | Establish session |
| `SESSION_CLOSE` | Both | Close session |

### Data Messages

| Message Type | Direction | Description |
|--------------|-----------|-------------|
| `FILE_REQUEST` | Client→Server | Request file transfer |
| `FILE_CHUNK` | Both | Transfer file in chunks |
| `FILE_COMPLETE` | Both | File transfer complete |
| `SCREEN_CAPTURE` | Server→Client | Send screen capture |
| `INPUT_EVENT` | Client→Server | Send user input |
| `NOTIFICATION` | Both | Sync notifications |

## Frame Format Examples

### Hello Message

```
Byte 0: 0x00 (server=0)
Byte 1: 0x01 (hello=1)
Bytes 2-5: Agent ID (e.g., "PANT")
Bytes 6-9: protobuf_len (0x00000005)
Bytes 10-14: Protobuf data (Header message)
```

### Device Discovery Request

```protobuf
Header {
    message_id: 1001,
    sequence_id: 12345,
    device_id: "12345678901234",
    session_id: "abc123xyz789"
}
```

### File Transfer Chunk

```protobuf
Header {
    message_id: 2001,
    sequence_id: 67890,
    device_id: "12345678901234",
    session_id: "abc123xyz789"
}

FileTransfer {
    file_id: "file123",
    filename: "document.pdf",
    filesize: 1048576,
    chunk_index: 0,
    chunk_data: "PDF header bytes...",
    total_chunks: 100
}
```

## Implementation Notes

### Frame Serialization

- All multi-byte values are stored in **little-endian** format
- No padding between frame fields
- Protobuf data is serialized using standard Protocol Buffers
- Frames are sent over TCP without additional framing (delimited by message length)

### Error Handling

- **Protocol Errors**: Invalid frame format, unsupported agent IDs
- **Transport Errors**: Connection timeouts, packet loss
- **Application Errors**: File not found, insufficient permissions
- **Recovery**: Automatic retransmission on timeout, session restart on critical errors

### Performance Considerations

- **Buffer Management**: Minimize memory allocations
- **Concurrency**: Use async I/O for non-blocking operations
- **Compression**: Consider compression for large payloads (images, video)
- **Caching**: Cache frequently accessed data (device info, certificates)

## Protocol Versions

### Current Version
- **Version**: 1.0
- **Revision**: 2
- **Date**: 2024-11-10
- **Changes**: Bug fixes, security enhancements, performance improvements

### Previous Versions
- **Version**: 0.9 - Initial protocol specification
- **Changes**: Basic device discovery and authentication

## Compatibility

### Supported Devices
- **OPPO Smartphones**: Reno series, Find series, A series
- **OPPO Tablets**: Kids tablets, Productivity tablets
- **Other Brands**: Xiaomi (via adapter), Realme (via adapter)

### Cross-Platform Support
- **OPPO Android**: Full compatibility
- **Xiaomi HyperOS**: Partial compatibility via adapters
- **Realme**: Limited compatibility
- **Windows**: Native client support
- **macOS**: Native client support
- **Linux**: This project (A+ Connect)

## References

1. **YueJie Connect**: OPPO/ColorOS reverse engineering implementation
2. **KDE Connect**: Open source reference implementation
3. **scrcpy**: Screen casting protocol reference
4. **OPPO Documentation**: Official OPPO developer documentation
5. **Protocol Buffers**: Google Protocol Buffers specification

## Future Enhancements

### Planned Features
1. **Enhanced Security**: Mutual TLS with certificate pinning
2. **Advanced File Operations**: File synchronization, collaboration
3. **Multi-Device Support**: Group device management
4. **API Integration**: REST API for programmatic access
5. **Mobile Extensions**: iOS and additional Android features

### Protocol Evolution
1. **Version 2.0**: Enhanced security, new message types
2. **Version 3.0**: Full duplex communication, zero-copy transfers
3. **Version 4.0**: WebRTC integration, browser-based clients

---

*This protocol specification is based on reverse engineering of OPPO binaries and analysis of the YueJie Connect implementation. All specifications are subject to change based on ongoing analysis and testing.*