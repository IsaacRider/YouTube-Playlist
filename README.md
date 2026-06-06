# Music Subscription Escape

A self-hosted music player that downloads YouTube videos as MP3s and plays them offline — available as a web app (PWA) or native Android/iOS app.

![Python](https://img.shields.io/badge/python-3.9+-blue) ![License](https://img.shields.io/badge/license-MIT-green)

## Features

- **YouTube Downloads** — Paste any YouTube video or playlist URL to download as MP3
- **Spotify Import** — Import Spotify playlists (finds and downloads matching YouTube audio)
- **YouTube Search** — Search and download songs without leaving the app
- **Shuffle & Playback** — Full player with shuffle, skip, seek, volume boost (up to 150%)
- **Playlists** — Create, rename, and manage playlists; auto-suggest playlists by genre
- **Offline Playback** — Sync tracks to your device for fully offline listening
- **Cross-Device Sync** — Bidirectional sync between all connected devices
- **Device Management** — View connected devices, lock access, monitor sync status
- **Native App** — Android/iOS app via Capacitor (real filesystem storage, no browser cache)
- **PWA** — Install from browser on any device as a progressive web app

## Prerequisites

- Python 3.9+
- [yt-dlp](https://github.com/yt-dlp/yt-dlp)

```bash
# macOS
brew install yt-dlp

# or via pip
pip install yt-dlp
```

For Spotify import (optional):
```bash
pip install spotapi
```

## Desktop Setup

1. **Clone the repo:**
   ```bash
   git clone https://github.com/IsaacRider/YouTube-Playlist.git
   cd YouTube-Playlist
   ```

2. **Start the server:**
   ```bash
   python3 server.py
   ```

3. **Open the player** — the server prints two URLs:
   ```
   Music Player Server
      Local:  http://localhost:8888/player.html
      Phone:  http://192.168.x.x:8888/player.html
   ```
   Open the **Local** URL in your browser.

4. **Add music** — Paste a YouTube URL (video or playlist) or Spotify playlist link and click **Add**.

## Phone Setup (PWA — Browser Install)

Your phone and computer must be on the **same WiFi network**.

1. Start the server on your computer.
2. Open Chrome/Safari on your phone and go to the **Phone URL** (e.g. `http://192.168.1.x:8888/player.html`).
3. **Install as an app:**
   - Chrome: menu → "Add to Home Screen" or "Install app"
   - Safari: Share → "Add to Home Screen"
4. **Sync tracks:** Hamburger menu → "Sync Between Devices" to download all tracks for offline use.

## Phone Setup (Native App — Recommended)

The native app stores music as real files on disk instead of browser cache, making offline playback reliable.

### Testing Locally

**Prerequisites:** Node.js 18+, Android Studio (for Android) or Xcode (for iOS)

```bash
# Install dependencies (already done if you see node_modules/)
npm install

# Sync web assets to native projects
npx cap sync

# Open in IDE
npx cap open android   # Opens Android Studio
npx cap open ios       # Opens Xcode
```

**Android (USB):**
1. Enable Developer Options on your phone (Settings → About → tap Build Number 7 times)
2. Enable USB Debugging in Developer Options
3. Connect phone via USB
4. In Android Studio, select your device from the device dropdown
5. Click the green Run button (or Shift+F10)
6. The app installs and launches on your phone

**Android (Wireless):**
1. Phone and computer on same WiFi
2. In Android Studio: File → Settings → Build → enable "Pair using WiFi"
3. On phone: Developer Options → Wireless Debugging → Pair
4. Run as above

**iOS:**
1. Open `ios/App/App.xcworkspace` in Xcode
2. Select your iPhone from the device list
3. Click Run
4. First time: trust the developer certificate on phone (Settings → General → Device Management)

### Building a Release APK

```bash
cd android
./gradlew assembleRelease
```

The APK will be at `android/app/build/outputs/apk/release/app-release-unsigned.apk`.

### First Launch

On first launch, the app will prompt for your server address (e.g. `http://192.168.1.100:8888`). It saves this and auto-connects on future launches. Once you sync, songs play offline without the server.

## Usage

### Adding Music
- **YouTube:** Paste a URL and tap Add, or use the search button to find songs
- **Spotify:** Paste a Spotify playlist URL — it imports all tracks via YouTube search
- **Upload:** Hamburger menu → Upload MP3s or Upload Playlist Folder

### Playlists
- Create playlists manually or use "Suggest Playlists by Genre" for auto-classification
- Rename playlists inline, add/remove tracks
- Playlists sync between all devices

### Syncing Between Devices
- Hamburger menu → "Sync Between Devices"
- Bidirectional: uploads local-only tracks to server, downloads missing tracks
- Progress shown on initiator; other devices see a sync overlay
- View Devices shows connected devices and their cached track counts

### Device Management
- Hamburger menu → "View Devices" to see all connected devices
- "Lock to Current Devices" restricts access to known IPs only

## Project Structure

| File/Dir | Purpose |
|----------|---------|
| `server.py` | Python HTTP server — YouTube/Spotify download, track management, sync coordination |
| `player.html` | Single-file PWA music player (web version) |
| `sw.js` | Service worker for PWA offline caching |
| `manifest.json` | PWA manifest for home screen install |
| `www/` | Web assets for native app (Capacitor) |
| `www/index.html` | Native app version of player (uses Filesystem API) |
| `android/` | Android native project (Capacitor) |
| `ios/` | iOS native project (Capacitor) |
| `capacitor.config.json` | Capacitor configuration |

## Development

After editing `www/index.html`, sync changes to native projects:
```bash
npx cap sync
```

To live-reload during development (avoids rebuilding native app):
```bash
npx cap run android --livereload --external
```

## Keyboard Shortcuts (Desktop)

| Key | Action |
|-----|--------|
| Space | Play / Pause |
| ← | Previous track |
| → | Next track |

## License

MIT
