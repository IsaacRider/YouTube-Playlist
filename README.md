# YouTube Playlist Offline Player

A self-hosted PWA music player that downloads YouTube videos as MP3s and plays them offline on any device — desktop or Android phone.

![Python](https://img.shields.io/badge/python-3.9+-blue) ![License](https://img.shields.io/badge/license-MIT-green)

## Features

- **YouTube Downloads** — Paste any YouTube video or playlist URL to download as MP3
- **Shuffle & Playback** — Full music player with shuffle, skip, seek, and volume controls
- **Playlists** — Create custom playlists, add/remove tracks, synced across all devices
- **PWA / Offline** — Install on your phone and sync tracks for offline playback
- **Cross-Device** — Phone and desktop share the same library and playlists
- **Zero Dependencies** — Single Python file server, single HTML file player, no build step

## Prerequisites

- Python 3.9+
- [yt-dlp](https://github.com/yt-dlp/yt-dlp)

```bash
# macOS
brew install yt-dlp

# or via pip
pip install yt-dlp
```

## Desktop Setup

1. **Clone the repo:**
   ```bash
   git clone https://github.com/IsaacRider/YouTube-Playlist.git
   cd YouTube-Playlist
   ```

2. **Start the server:**
   ```bash
   ./start.sh
   ```
   Or manually:
   ```bash
   python3 server.py
   ```

3. **Open the player** — the server prints two URLs:
   ```
   🎵 Music Player Server
      Local:  http://localhost:8888/player.html
      Phone:  http://192.168.x.x:8888/player.html
   ```
   Open the **Local** URL in your browser. That's it — you're running the desktop app.

4. **Add music** — Paste a YouTube URL (video or playlist) into the input field and click **Add**.

## Android Phone Setup

Your phone and computer must be on the **same WiFi network**.

1. **Start the server on your computer** (see Desktop Setup above).

2. **Open Chrome on your Android phone** and go to the **Phone URL** printed by the server (e.g. `http://192.168.1.x:8888/player.html`).

3. **Install as an app:**
   - Tap the Chrome menu (three dots, top right)
   - Tap **"Add to Home Screen"** or **"Install app"**
   - Tap **Add** — the app icon appears on your home screen

4. **Sync tracks for offline playback:**
   - In the player, tap the **Sync** button
   - Wait for all tracks to download to your phone's browser cache
   - The progress bar shows sync status; green dots (●) appear next to cached tracks

5. **Go offline** — After syncing, the app works without the server. You can close your laptop, leave the house, etc. Music plays from the phone's local cache.

6. **Add new music later:**
   - Start the server on your computer again
   - Open the app on your phone (it auto-reconnects when the server is available)
   - Download new YouTube links or tap **Sync** to pull any new tracks

## Usage

### Adding Music
Paste a YouTube URL into the text box at the top and press **Enter** or tap **Add**. Works with both individual videos and full playlists.

### Playlists
- Tap the **Playlists** tab to create and manage playlists
- Tap **+** next to any track to add it to a playlist
- Playlists sync between desktop and phone automatically

### Syncing Between Devices
Both devices share the same music library and playlists through the server. When connected:
- New tracks downloaded on desktop are available to the phone after tapping **Sync**
- Playlists created on the phone appear on desktop (and vice versa)
- The green/red dot in the top right shows connection status

## How It Works

| File | Purpose |
|------|---------|
| `server.py` | Python HTTP server — YouTube download API, track listing, playlist storage, ZIP export |
| `player.html` | Single-file PWA music player with mobile-first UI |
| `sw.js` | Service worker for offline caching |
| `manifest.json` | PWA manifest for home screen install |
| `start.sh` | Convenience launcher |

## Keyboard Shortcuts (Desktop)

| Key | Action |
|-----|--------|
| Space | Play / Pause |
| ← | Previous track |
| → | Next track |

## License

MIT
