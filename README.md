# YouTube Playlist Offline Player

A self-hosted PWA music player that downloads YouTube videos as MP3s and plays them offline on any device.

![Python](https://img.shields.io/badge/python-3.9+-blue) ![License](https://img.shields.io/badge/license-MIT-green)

## Features

- **YouTube Downloads** — Paste any YouTube video or playlist URL to download as MP3
- **Shuffle & Playback** — Full music player with shuffle, skip, seek, and volume controls
- **PWA / Offline** — Install on your phone via "Add to Home Screen" and sync tracks for offline playback
- **Zero Dependencies** — Single Python file server, single HTML file player, no build step
- **Cross-Device** — Access from any device on your local network

## Quick Start

### Prerequisites

- Python 3.9+
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (`brew install yt-dlp` or `pip install yt-dlp`)

### Run

```bash
./start.sh
```

Or manually:

```bash
python3 server.py
```

The server prints two URLs:

```
🎵 Music Player Server
   Local:  http://localhost:8888/player.html
   Phone:  http://192.168.x.x:8888/player.html
```

Open the **Phone** URL on your mobile device (same WiFi network), then use Chrome's **"Add to Home Screen"** to install as an app.

## Usage

1. **Add music** — Paste a YouTube URL into the input field and tap Download
2. **Play** — Tap any track or hit shuffle
3. **Sync offline** — Tap the Sync button to cache all tracks to your device
4. **Go offline** — Close your laptop; the player works from cache

## How It Works

| File | Purpose |
|------|---------|
| `server.py` | Python HTTP server with YouTube download API (`/api/download`), track listing (`/api/tracks`), and ZIP export (`/download-all`) |
| `player.html` | Single-file PWA music player with mobile-first UI |
| `sw.js` | Service worker for offline caching |
| `manifest.json` | PWA manifest for home screen install |
| `start.sh` | Convenience launcher |

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| Space | Play / Pause |
| ← | Previous track |
| → | Next track |

## License

MIT
