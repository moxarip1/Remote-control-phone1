# HomeVault Remote

**HomeVault Remote** turns an old Android phone left at home (on charger + Wi-Fi) into a **private, self-hosted encrypted personal cloud storage server**, and lets your main phone access, manage, upload, stream, and download files over local Wi-Fi or remotely over mobile data without a PC.

---

## Architecture

```text
┌───────────────────────────┐            LAN (mDNS/NSD + Pinned TLS)             ┌──────────────────────────────┐
│    Remote Client Mode     │ ─────────────────────────────────────────────────▶ │       Home Server Mode       │
│     (Your Main Phone)     │                                                    │     (Old Phone at Home)      │
│                           │     Internet (Mobile Data -> Modular Relay)        │                              │
│  Compose UI -> ViewModel  │ ────▶ [ Self-Hosted Relay (relay/) ] ◀════════════ │  Foreground Service (TLS)    │
│  ClientApi -> TransferMgr │         HTTPS /relay/{deviceId}/*     Outbound WSS │  SAF StorageProvider + Auth  │
└───────────────────────────┘                                                    └──────────────────────────────┘
```

### Module & Package Structure
- `com.example.security`: Pure-Kotlin X.509 v3 self-signed TLS certificate generator + SHA-256 fingerprint pinning, Android Keystore AES-256-GCM encryption, PBKDF2-HMAC-SHA256 PIN hashing, `PathSanitizer` traversal guard, sliding-window `RateLimiter`, and redacted `SecureVaultLogger`.
- `com.example.storage`: Storage Access Framework (`SafStorageProvider`) with persistent tree URI permissions and local directory provider (`LocalDirectoryStorageProvider`), streaming 64 KB buffered I/O, chunked staging with SHA-256 verification before commit, lazy memory-safe thumbnails, and glob search (`*.mp4`, `*.pdf`).
- `com.example.server`: Embedded `HomeVaultHttpServer` (TLS + Range byte streaming), `SessionManager` (15-min short-lived access tokens, rotating refresh tokens, single-use pairing codes, anti-replay nonce/timestamp checks), `RelayTunnelClient` (outbound WebSocket tunnel requiring zero router port forwarding), `NsdDiscoveryService` (`_homevault._tcp.`), `HomeVaultServerService` (`dataSync` Foreground Service), and `BootCompletedReceiver`.
- `com.example.network` & `com.example.client`: `HomeVaultTlsClient`, `ConnectionRouter` (`LAN -> Direct -> Relay` auto-selection with exponential backoff), and `HomeVaultClientApi` with automatic token refresh.
- `com.example.transfer`: `TransferManager` supporting chunked resumable uploads, HTTP `Range` resumable downloads, SHA-256 checksum verification, Pause/Resume/Cancel/Retry, Wi-Fi-only toggle, live speed & ETA, and Room persistence.
- `com.example.database`: Room database (`PairedServerEntity`, `TransferEntity`, `RecentFileEntity`, `ServerActivityEntity`) + DataStore `SettingsStore`.
- `com.example.ui`: Material 3 Edge-to-Edge Jetpack Compose UI with Dark/Light modes, adaptive `NavigationBar` / `NavigationRail`, File Manager, Media3 video/audio streaming preview, `PdfRenderer` PDF preview, Storage Donut Dashboard, QR Scanner, and Settings.
- `relay/`: Zero-dependency Node.js WebSocket-to-HTTP relay server with `Dockerfile`, `docker-compose.yml`, and automated tests.

---

## Setup & Usage

### 1. Home Server Mode (Old Phone at Home)
1. Launch **HomeVault Remote** and tap **This phone is Home Server**.
2. Complete the 5-step setup wizard:
   - **Device Name**: e.g., `Home Storage`.
   - **Storage Folder**: Pick any folder using Android Storage Access Framework (SAF). Persistent URI permissions are granted cleanly without requesting broad legacy storage permissions.
   - **PIN**: Set a strong PIN (hashed via PBKDF2, never stored in plaintext).
   - **Network & Relay**: Optionally enter your self-hosted Relay URL (for outside-home Mobile Data access) and toggle **Start server automatically on boot**.
   - **Start Server**: Starts the `HomeVaultServerService` foreground service (`HomeVault is running` notification with **Open App** & **Stop Server** actions).
3. Tap **Pair New Device** to display the encrypted QR code (without embedding the PIN) and 6-digit pairing code.

### 2. Remote Client Mode (Main Phone)
1. Launch **HomeVault Remote** and tap **Remote Client**.
2. Open **My Devices** (`Devices` icon) and pair via:
   - **Automatic LAN Discovery** (`_homevault._tcp.` mDNS/NSD on the same Wi-Fi),
   - **Pair with QR Code** (CameraX + ZXing offline scanner), or
   - **Manual Pairing** (IP/Relay + 6-digit Pairing Code + PIN).
3. Browse folders, upload via Android Photo Picker or SAF Document Picker, download to chosen storage locations with pause/resume, preview images/PDFs/text, stream audio/video via Media3 ExoPlayer, search (`*.mp4`, `*.jpg`), and monitor storage usage.

---

## LAN vs. Remote Internet Access (No Port Forwarding)
- **Same Wi-Fi (LAN)**: Client discovers the server automatically via NSD or saved local IP and connects directly over TLS with SHA-256 certificate fingerprint pinning.
- **Outside Home (Mobile Data)**: The Home Server phone maintains an **outbound** WebSocket tunnel (`wss://<your-relay>/ws/tunnel`) to your modular Relay server (`relay/`). Because the connection is outbound, **no router port forwarding is required**.
- **Automatic Route Priority**: `LAN -> Direct URL -> Relay`, falling back seamlessly when you leave home Wi-Fi.

---

## Deploying the Modular Relay Backend (`relay/`)

### Docker Compose
```bash
cd relay
cp .env.example .env
docker compose up -d --build
```

### Node.js Directly
```bash
cd relay
npm test
npm start
```
Put Nginx, Caddy, or Cloudflare Tunnel in front of port `8787` for TLS termination (`https://` / `wss://`). See `relay/README.md` for environment variables (`PORT`, `RELAY_SHARED_SECRET`, `RELAY_RATE_LIMIT_MAX`, etc.).

---

## Security Design
- **TLS & Pinning**: Direct server connections use TLS 1.2/1.3 with SHA-256 certificate fingerprint verification.
- **Zero-Trust Auth**: Pairing uses single-use expiring codes + PBKDF2-verified PINs. API requests use short-lived Bearer access tokens (15 min) with rotating refresh tokens and `X-HV-Nonce` + `X-HV-Timestamp` anti-replay protection.
- **Keystore Encryption**: Tokens and PIN hashes are encrypted at rest using Android Keystore (`AES/GCM/NoPadding`).
- **Path Traversal Guard**: `PathSanitizer` rejects `..`, `%2e%2e`, backslashes, drive prefixes, and null bytes; local paths verify canonical containment inside the selected root.
- **Rate Limiting**: Brute-force PIN/pairing attempts trigger temporary lockouts (`HTTP 429`).
- **Data Integrity**: Uploads are staged in temporary `.part` files and verified by byte count + SHA-256 checksum before committing to user storage. Downloads verify `X-File-Sha256` before finalizing.

---

## Permissions & Battery Optimization
- **Minimal Permissions**: Uses Android Photo Picker (`PickVisualMedia`) and SAF (`OpenDocumentTree`, `OpenMultipleDocuments`, `CreateDocument`). Never requests `READ_EXTERNAL_STORAGE` or `READ_MEDIA_*`. `CAMERA` is requested at runtime only when scanning QR codes; `POST_NOTIFICATIONS` on Android 13+ for foreground server status.
- **Background Operation**: Uses `foregroundServiceType="dataSync"` and a partial wake lock while active. For 24/7 reliability on aggressive OEM ROMs, use **Settings → Battery → Open Battery Optimization Settings** and set HomeVault Remote to *Unrestricted*.

---

## Prebuilt APK & Local Builds

### Ready-to-Install Debug APK
A prebuilt debug APK is available directly at **`debug/debug.apk`** and is automatically updated whenever a local Gradle debug build runs.

### Local Gradle Build & Tests
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` and mirrored to `debug/debug.apk`.

---

## Troubleshooting
- **"Connection lost. Retrying..."**: Ensure the Home Server phone is awake/charging with battery optimization set to Unrestricted, or verify your Relay URL if on Mobile Data.
- **"Access denied. Please reconnect the Home Storage."**: Sessions may have been revoked or the server PIN was changed. Re-authenticate or re-pair from **My Devices**.
- **LAN server not auto-discovered**: Some public/guest Wi-Fi routers block mDNS multicast. Use **Pair with QR Code** or enter the local IP shown on the Server Dashboard manually.
