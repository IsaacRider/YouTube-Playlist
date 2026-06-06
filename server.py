#!/usr/bin/env python3
import http.server
import io
import json
import os
import re
import socket
import socketserver
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

PORT = 8888
DIR = os.path.dirname(os.path.abspath(__file__))
PLAYLISTS_FILE = os.path.join(DIR, 'playlists.json')
METADATA_FILE = os.path.join(DIR, 'metadata.json')
YTDLP = '/opt/homebrew/bin/yt-dlp'
os.chdir(DIR)

def load_json(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return {}

def save_json(path, data):
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)

def load_playlists():
    return load_json(PLAYLISTS_FILE)

def save_playlists(data):
    save_json(PLAYLISTS_FILE, data)

def load_metadata():
    return load_json(METADATA_FILE)

def save_metadata(data):
    save_json(METADATA_FILE, data)

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return 'localhost'

def rebuild_filelist():
    files = sorted(f for f in os.listdir(DIR) if f.endswith('.mp3'))
    with open(os.path.join(DIR, 'filelist.txt'), 'w') as fh:
        fh.write('\n'.join(files) + '\n')
    return files

def get_existing_mp3s():
    return set(f for f in os.listdir(DIR) if f.endswith('.mp3'))

def _extract_video_id(url):
    m = re.search(r'[?&]v=([a-zA-Z0-9_-]{11})', url)
    if m:
        return m.group(1)
    m = re.search(r'youtu\.be/([a-zA-Z0-9_-]{11})', url)
    if m:
        return m.group(1)
    return None

def _same_video(url1, url2):
    id1 = _extract_video_id(url1)
    id2 = _extract_video_id(url2)
    return id1 and id2 and id1 == id2

connected_devices = {}  # ip -> {name, last_seen, user_agent}
allowed_devices = None  # None = unlocked, set(...) = locked to these IPs
sync_state = {'active': False, 'initiator': None, 'progress': 0, 'total': 0, 'phase': '', 'device': ''}

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def _register_device(self):
        global allowed_devices
        ip = self.client_address[0]
        if ip == '127.0.0.1' or ip == '::1':
            ip = get_local_ip()
        if allowed_devices is not None and ip not in allowed_devices:
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b'{"error":"Device not authorized"}')
            return False
        ua = self.headers.get('User-Agent', '')
        name = 'Unknown'
        if 'iPhone' in ua: name = 'iPhone'
        elif 'iPad' in ua: name = 'iPad'
        elif 'Android' in ua: name = 'Android'
        elif 'Macintosh' in ua or 'Mac OS' in ua: name = 'Mac'
        elif 'Windows' in ua: name = 'Windows PC'
        elif 'Linux' in ua: name = 'Linux'
        existing = connected_devices.get(ip, {})
        connected_devices[ip] = {'name': name, 'ip': ip, 'last_seen': time.time(), 'user_agent': ua, 'cached': existing.get('cached', 0)}
        return True

    def do_GET(self):
        if not self._register_device(): return
        if self.path == '/api/devices':
            cutoff = time.time() - 300
            active = [d for d in connected_devices.values() if d['last_seen'] >= cutoff]
            total = len([f for f in os.listdir(DIR) if f.endswith('.mp3')])
            server_ip = get_local_ip()
            for d in active:
                d['ago'] = int(time.time() - d['last_seen'])
                if d['ip'] == server_ip:
                    d['cached'] = total
            self._json(200, {'devices': active, 'locked': allowed_devices is not None})
            return
        elif self.path == '/api/sync-status':
            self._json(200, sync_state)
            return
        elif self.path == '/api/tracks':
            mp3s = [f for f in os.listdir(DIR) if f.endswith('.mp3')]
            files = sorted(mp3s, key=lambda f: os.path.getmtime(os.path.join(DIR, f)), reverse=True)
            sizes = {}
            ids = {}
            for f in files:
                try:
                    sz = os.path.getsize(os.path.join(DIR, f))
                    sizes[f] = sz
                    ids[f] = str(sz)
                except OSError:
                    sizes[f] = 0
                    ids[f] = '0'
            self._json(200, {'tracks': files, 'sizes': sizes, 'ids': ids})
        elif self.path == '/api/playlists':
            self._json(200, load_playlists())
        elif self.path == '/api/metadata':
            self._json(200, load_metadata())
        elif self.path == '/download-all':
            self._serve_zip()
        elif self.path == '/export-all':
            self._serve_export_zip()
        else:
            super().do_GET()

    def do_DELETE(self):
        if not self._register_device(): return
        if self.path == '/api/tracks/all':
            mp3s = [f for f in os.listdir(DIR) if f.endswith('.mp3')]
            for f in mp3s:
                os.remove(os.path.join(DIR, f))
            save_playlists({})
            save_metadata({})
            rebuild_filelist()
            print(f"🗑 Deleted all {len(mp3s)} tracks")
            self._json(200, {'ok': True, 'deleted': len(mp3s)})
        elif self.path.startswith('/api/tracks/'):
            filename = urllib.parse.unquote(self.path[len('/api/tracks/'):])
            filepath = os.path.join(DIR, filename)
            if os.path.exists(filepath) and filename.endswith('.mp3'):
                os.remove(filepath)
                # Remove from playlists
                playlists = load_playlists()
                changed = False
                for name in playlists:
                    if filename in playlists[name]:
                        playlists[name] = [t for t in playlists[name] if t != filename]
                        changed = True
                if changed:
                    save_playlists(playlists)
                # Remove from metadata
                meta = load_metadata()
                if filename in meta:
                    del meta[filename]
                    save_metadata(meta)
                rebuild_filelist()
            self._json(200, {'ok': True})
        elif self.path.startswith('/api/playlists/'):
            name = urllib.parse.unquote(self.path[len('/api/playlists/'):])
            playlists = load_playlists()
            if name in playlists:
                del playlists[name]
                save_playlists(playlists)
            self._json(200, {'ok': True})
        else:
            self.send_error(404)

    def do_POST(self):
        if not self._register_device(): return
        if self.path == '/api/devices/lock':
            global allowed_devices
            if allowed_devices is not None:
                allowed_devices = None
                print("🔓 Devices unlocked")
                self._json(200, {'ok': True, 'locked': False})
            else:
                cutoff = time.time() - 300
                allowed_devices = {ip for ip, d in connected_devices.items() if d['last_seen'] >= cutoff}
                print(f"🔒 Devices locked to: {allowed_devices}")
                self._json(200, {'ok': True, 'locked': True})
            return
        elif self.path == '/api/device-status':
            ip = self.client_address[0]
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            if ip in connected_devices:
                connected_devices[ip]['cached'] = body.get('cached', 0)
            self._json(200, {'ok': True})
            return
        elif self.path == '/api/sync-start':
            ip = self.client_address[0]
            device_name = connected_devices.get(ip, {}).get('name', 'Unknown')
            sync_state.update({'active': True, 'initiator': ip, 'progress': 0, 'total': 0, 'phase': 'Starting...', 'device': device_name})
            print(f"🔄 Sync started by {device_name} ({ip})")
            self._json(200, {'ok': True})
            return
        elif self.path == '/api/sync-progress':
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            sync_state['progress'] = body.get('progress', 0)
            sync_state['total'] = body.get('total', 0)
            sync_state['phase'] = body.get('phase', '')
            self._json(200, {'ok': True, 'stopped': not sync_state['active']})
            return
        elif self.path == '/api/sync-stop':
            sync_state.update({'active': False, 'initiator': None, 'progress': 0, 'total': 0, 'phase': '', 'device': ''})
            print("⏹ Sync stopped")
            self._json(200, {'ok': True})
            return
        elif self.path == '/api/playlists':
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            name = body.get('name', '').strip()
            tracks_list = body.get('tracks', [])
            if not name:
                self._json(400, {'error': 'Playlist name required'})
                return
            playlists = load_playlists()
            playlists[name] = tracks_list
            save_playlists(playlists)
            self._json(200, {'ok': True})
        elif self.path == '/api/download':
            self._handle_download()
        elif self.path == '/api/search':
            self._handle_search()
        elif self.path == '/api/spotify-playlist':
            self._handle_spotify_playlist()
        elif self.path == '/api/download-search':
            self._handle_download_search()
        elif self.path == '/api/upload':
            self._handle_upload()
        elif self.path == '/api/rename-playlist':
            self._handle_rename_playlist()
        elif self.path == '/api/suggest-playlists':
            self._handle_suggest_playlists()
        else:
            self.send_error(404)

    def _handle_search(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length))
        query = body.get('query', '').strip()
        if not query:
            self._json(400, {'error': 'Search query required'})
            return
        print(f"\n🔍 Searching for: \"{query}\"")
        try:
            t0 = time.time()
            result = subprocess.run(
                [YTDLP, f'ytsearch10:{query}', '--flat-playlist',
                 '--print', '%(id)s\t%(title)s'],
                capture_output=True, text=True, timeout=15, cwd=DIR
            )
            elapsed = time.time() - t0
            print(f"   yt-dlp finished in {elapsed:.1f}s (exit code {result.returncode})")
            if result.stderr.strip():
                print(f"   stderr: {result.stderr.strip()[:200]}")
            results = []
            for line in result.stdout.strip().split('\n'):
                if not line or '\t' not in line:
                    continue
                vid_id, title = line.split('\t', 1)
                if vid_id and title:
                    results.append({
                        'title': title,
                        'id': vid_id,
                        'url': f'https://www.youtube.com/watch?v={vid_id}'
                    })
            print(f"   Found {len(results)} results")
            for r in results:
                print(f"     • {r['title']}")
            self._json(200, {'results': results})
        except subprocess.TimeoutExpired:
            print("   ⚠ Search timed out after 15s")
            self._json(500, {'error': 'Search timed out'})
        except FileNotFoundError:
            print("   ⚠ yt-dlp not found!")
            self._json(500, {'error': 'yt-dlp not found'})

    def _handle_spotify_playlist(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length))
        url = body.get('url', '').strip()
        if not url or 'spotify.com' not in url:
            self._json(400, {'error': 'Invalid Spotify URL'})
            return

        m = re.search(r'playlist/([a-zA-Z0-9]+)', url)
        if not m:
            self._json(400, {'error': 'Could not find playlist ID in URL'})
            return
        playlist_id = m.group(1)
        print(f"\n🎵 Fetching Spotify playlist: {playlist_id}")

        # Use spotapi first (fast, no YouTube resolution), fall back to spotdl save
        script = f'''
import json, sys
try:
    from spotapi import PublicPlaylist
    pl = PublicPlaylist("{playlist_id}")
    tracks = []
    offset = 0
    pl_name = ""
    while True:
        info = pl.get_playlist_info(offset=offset, limit=100)
        if not pl_name:
            pl_name = info.get("data", {{}}).get("playlistV2", {{}}).get("name", "")
        items = info["data"]["playlistV2"]["content"]["items"]
        if not items:
            break
        for item in items:
            track = item.get("itemV2", {{}}).get("data", {{}})
            name = track.get("name", "")
            artists_data = track.get("artists", {{}}).get("items", [])
            artists = [a.get("profile", {{}}).get("name", "") for a in artists_data]
            if name:
                artist_str = ", ".join(artists)
                tracks.append({{"name": name, "artist": artist_str, "query": artist_str + " - " + name if artist_str else name}})
        offset += len(items)
        total = info["data"]["playlistV2"]["content"].get("totalCount", 0)
        if offset >= total:
            break
    print(json.dumps({{"tracks": tracks, "name": pl_name}}))
except Exception as e:
    print("FALLBACK:" + str(e), file=sys.stderr)
    sys.exit(1)
'''
        venv_python = os.path.join(DIR, '.venv', 'bin', 'python3')
        spotdl_path = os.path.join(DIR, '.venv', 'bin', 'spotdl')
        if not os.path.exists(venv_python):
            self._json(500, {'error': 'Project .venv not found. Run: python3 -m venv .venv && .venv/bin/pip install spotdl'})
            return

        tracks = None
        playlist_name = ''

        # Try fast method first (spotapi)
        try:
            print("   Trying fast method (spotapi)...")
            t0 = time.time()
            result = subprocess.run(
                [venv_python, '-c', script],
                capture_output=True, text=True, timeout=30, cwd=DIR
            )
            if result.returncode == 0 and result.stdout.strip():
                parsed = json.loads(result.stdout)
                if isinstance(parsed, dict):
                    tracks = parsed.get('tracks', [])
                    playlist_name = parsed.get('name', '')
                elif isinstance(parsed, list):
                    tracks = parsed
                if tracks:
                    print(f"   Fast method succeeded in {time.time() - t0:.1f}s")
                else:
                    print(f"   Fast method returned 0 tracks, falling back to spotdl...")
            else:
                print(f"   Fast method failed ({result.stderr.strip()[:100]}), falling back to spotdl...")
        except (subprocess.TimeoutExpired, Exception) as e:
            print(f"   Fast method error: {e}, falling back to spotdl...")

        # Fallback: spotdl save (slower but more reliable)
        if not tracks:
            try:
                import tempfile
                tmp = tempfile.mktemp(suffix='.spotdl')
                print(f"   Running spotdl save (this may take a few minutes for large playlists)...")
                t0 = time.time()
                result = subprocess.run(
                    [spotdl_path, 'save', url, '--save-file', tmp],
                    capture_output=True, text=True, timeout=300, cwd=DIR
                )
                print(f"   spotdl finished in {time.time() - t0:.1f}s (exit code {result.returncode})")
                if result.returncode != 0:
                    print(f"   stderr: {result.stderr[:300]}")
                    self._json(500, {'error': result.stderr[:300] or 'Failed to read Spotify playlist'})
                    return
                with open(tmp) as f:
                    track_data = json.load(f)
                os.remove(tmp)
                tracks = []
                for t in track_data:
                    name = t.get('name', '')
                    artists = ', '.join(t.get('artists', []))
                    if name:
                        tracks.append({'name': name, 'artist': artists, 'query': f'{artists} - {name}' if artists else name})
            except subprocess.TimeoutExpired:
                print("   ⚠ spotdl timed out after 5 minutes")
                self._json(500, {'error': 'Spotify fetch timed out. Try a smaller playlist.'})
                return
            except Exception as e:
                print(f"   ⚠ Error: {e}")
                self._json(500, {'error': str(e)[:300]})
                return

        if not tracks:
            self._json(500, {'error': 'Could not read Spotify playlist'})
            return

        print(f"   Found {len(tracks)} tracks:")
        for t in tracks[:10]:
            print(f"     • {t['query']}")
        if len(tracks) > 10:
            print(f"     ... and {len(tracks) - 10} more")
        self._json(200, {'tracks': tracks, 'name': playlist_name or f'Spotify ({len(tracks)} tracks)'})

    def _handle_download_search(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length))
        query = body.get('query', '').strip()
        if not query:
            self._json(400, {'error': 'Search query required'})
            return
        print(f"\n⬇ Download by search: \"{query}\"")

        # Check if a similar file already exists
        existing = get_existing_mp3s()
        query_lower = query.lower()
        for f in existing:
            name = f.replace('.mp3', '').lower()
            # Check if query words mostly match the filename
            query_words = set(re.sub(r'[^\w\s]', '', query_lower).split())
            name_words = set(re.sub(r'[^\w\s]', '', name).split())
            if query_words and name_words:
                overlap = len(query_words & name_words) / len(query_words)
                if overlap >= 0.7:
                    print(f"   ⏭ Skipped (already have): {f}")
                    self._json(200, {'ok': True, 'added': 0, 'tracks': [], 'skipped': True, 'existing': f})
                    return

        before = existing
        template = '%(title)s.%(ext)s'
        try:
            # Pre-check: get duration and video ID to filter non-music and save metadata
            precheck_vid_id = None
            check = subprocess.run(
                [YTDLP, f'ytsearch1:{query}', '--print', '%(duration)s\t%(id)s\t%(title)s',
                 '--no-download'],
                capture_output=True, text=True, timeout=15, cwd=DIR
            )
            if check.returncode == 0 and check.stdout.strip():
                parts = check.stdout.strip().split('\t', 2)
                if len(parts) >= 2:
                    precheck_vid_id = parts[1].strip()
                if len(parts) >= 1:
                    try:
                        duration = int(parts[0])
                        title = parts[2] if len(parts) >= 3 else query
                        if duration < 30:
                            print(f"   ⏭ Skipped (too short, {duration}s — likely not music): {title}")
                            self._json(200, {'ok': True, 'added': 0, 'tracks': [], 'skipped': True, 'reason': f'Not music (only {duration}s long)'})
                            return
                        if duration > 1200:
                            print(f"   ⏭ Skipped (too long, {duration}s — likely not a song): {title}")
                            self._json(200, {'ok': True, 'added': 0, 'tracks': [], 'skipped': True, 'reason': f'Not music ({duration // 60}m long)'})
                            return
                    except ValueError:
                        pass

            # Download using the specific video URL if we have it, to ensure metadata matches
            search_arg = f'https://www.youtube.com/watch?v={precheck_vid_id}' if precheck_vid_id else f'ytsearch1:{query}'
            t0 = time.time()
            result = subprocess.run(
                [YTDLP, '-x', '--audio-format', 'mp3', '--audio-quality', '0',
                 '-o', template, '--no-overwrites', search_arg],
                capture_output=True, text=True, timeout=120, cwd=DIR
            )
            print(f"   Download finished in {time.time() - t0:.1f}s (exit code {result.returncode})")
            after = get_existing_mp3s()
            new_files = sorted(after - before)
            if result.returncode != 0 and not new_files:
                print(f"   ⚠ Download failed: {result.stderr[:200]}")
                self._json(500, {'error': result.stderr[:200] or 'Download failed'})
                return
            # Record metadata with YouTube URL from pre-check
            meta = load_metadata()
            if new_files and precheck_vid_id:
                for f in new_files:
                    meta[f] = {'youtube_url': f'https://www.youtube.com/watch?v={precheck_vid_id}'}
                save_metadata(meta)
            rebuild_filelist()
            print(f"   ✅ Added {len(new_files)} track(s): {', '.join(new_files)}")
            self._json(200, {'ok': True, 'added': len(new_files), 'tracks': new_files})
        except subprocess.TimeoutExpired:
            print("   ⚠ Download timed out")
            rebuild_filelist()
            self._json(500, {'error': 'Download timed out'})
        except FileNotFoundError:
            print("   ⚠ yt-dlp not found!")
            self._json(500, {'error': 'yt-dlp not found'})

    def _handle_download(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length))
        url = body.get('url', '').strip()
        if not url or ('youtube' not in url and 'youtu.be' not in url):
            self._json(400, {'error': 'Invalid YouTube URL'})
            return

        print(f"\n⬇ Download requested: {url}")

        # Check if this URL was already downloaded
        if not ('/playlist?' in url):
            meta = load_metadata()
            for fname, info in meta.items():
                existing_url = info.get('youtube_url', '')
                if existing_url and _same_video(url, existing_url):
                    print(f"   ⚠ Already downloaded as: {fname}")
                    self._json(400, {'error': 'You have already downloaded this.'})
                    return

        before = get_existing_mp3s()
        template = '%(title)s.%(ext)s'
        is_playlist = '/playlist?' in url

        try:
            # First get video info to record URLs
            print("   Fetching video info...")
            t0 = time.time()
            info_cmd = [YTDLP, '--dump-json']
            if is_playlist:
                info_cmd.append('--flat-playlist')
            else:
                info_cmd.append('--no-playlist')
            info_cmd.append(url)
            info_result = subprocess.run(
                info_cmd, capture_output=True, text=True, timeout=60, cwd=DIR
            )
            print(f"   Video info fetched in {time.time() - t0:.1f}s")
            video_urls = {}
            for line in info_result.stdout.strip().split('\n'):
                if not line:
                    continue
                try:
                    info = json.loads(line)
                    title = info.get('title', '')
                    vid_id = info.get('id', '')
                    if title and vid_id:
                        video_urls[title] = f'https://www.youtube.com/watch?v={vid_id}'
                        print(f"     • {title}")
                except json.JSONDecodeError:
                    pass

            # Download
            print("   Downloading MP3...")
            t0 = time.time()
            playlist_flag = '--yes-playlist' if is_playlist else '--no-playlist'
            result = subprocess.run(
                [YTDLP, '-x', '--audio-format', 'mp3', '--audio-quality', '0',
                 '-o', template, '--no-overwrites', playlist_flag, url],
                capture_output=True, text=True, timeout=600, cwd=DIR
            )
            print(f"   Download finished in {time.time() - t0:.1f}s (exit code {result.returncode})")

            after = get_existing_mp3s()
            new_files = sorted(after - before)

            if result.returncode != 0 and not new_files:
                print(f"   ⚠ Download failed: {result.stderr[:200]}")
                self._json(500, {'error': result.stderr[:300]})
                return

            # Record metadata (YouTube URL for each new file)
            meta = load_metadata()
            for f in new_files:
                title = f.replace('.mp3', '')
                # Try exact match first, then fuzzy
                yt_url = video_urls.get(title, '')
                if not yt_url:
                    for vt, vu in video_urls.items():
                        if vt.lower() in title.lower() or title.lower() in vt.lower():
                            yt_url = vu
                            break
                if yt_url:
                    meta[f] = {'youtube_url': yt_url}
            # Also store the source URL for single videos
            if len(new_files) == 1 and 'watch' in url:
                meta[new_files[0]] = {'youtube_url': url}
            save_metadata(meta)

            files = rebuild_filelist()

            print(f"   ✅ Added {len(new_files)} track(s): {', '.join(new_files)}")

            self._json(200, {
                'ok': True,
                'added': len(new_files),
                'tracks': new_files,
                'total': len(files)
            })
        except subprocess.TimeoutExpired:
            print("   ⚠ Download timed out")
            rebuild_filelist()
            self._json(500, {'error': 'Download timed out (some tracks may have saved)'})
        except FileNotFoundError:
            print("   ⚠ yt-dlp not found!")
            self._json(500, {'error': 'yt-dlp not found. Install via: brew install yt-dlp'})

    def _handle_rename_playlist(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length))
        old_name = body.get('old_name', '').strip()
        new_name = body.get('new_name', '').strip()
        if not old_name or not new_name:
            self._json(400, {'error': 'Both old_name and new_name required'})
            return
        playlists = load_playlists()
        if old_name not in playlists:
            self._json(404, {'error': 'Playlist not found'})
            return
        if new_name in playlists and new_name != old_name:
            self._json(400, {'error': 'A playlist with that name already exists'})
            return
        playlists[new_name] = playlists.pop(old_name)
        save_playlists(playlists)
        print(f"   ✏️ Renamed playlist: {old_name} → {new_name}")
        self._json(200, {'ok': True})

    def _handle_suggest_playlists(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length))
        track_names = body.get('tracks', [])
        if not track_names:
            self._json(400, {'error': 'No tracks provided'})
            return

        print(f"\n🎯 Suggesting playlists for {len(track_names)} tracks...")
        meta = load_metadata()

        GENRE_KEYWORDS = {
            'Country': ['country', 'nashville', 'bluegrass', 'honky tonk', 'western', 'americana'],
            'Rock': ['rock', 'alternative', 'grunge', 'punk', 'indie rock', 'classic rock', 'hard rock', 'metal'],
            'Pop': ['pop', 'top 40', 'dance pop', 'synth pop', 'electropop', 'teen pop'],
            'Hip Hop': ['hip hop', 'rap', 'trap', 'r&b', 'rnb', 'hiphop', 'hip-hop'],
            'R&B': ['r&b', 'rnb', 'soul', 'neo soul', 'rhythm and blues', 'motown'],
            'Electronic': ['electronic', 'edm', 'house', 'techno', 'trance', 'dubstep', 'drum and bass', 'dnb', 'ambient'],
            'Jazz': ['jazz', 'smooth jazz', 'bebop', 'swing', 'blues jazz'],
            'Blues': ['blues', 'delta blues', 'chicago blues'],
            'Classical': ['classical', 'orchestra', 'symphony', 'piano', 'concerto', 'sonata', 'opera'],
            'Latin': ['latin', 'reggaeton', 'salsa', 'bachata', 'cumbia', 'bossa nova', 'latin pop'],
            'Reggae': ['reggae', 'ska', 'dancehall', 'dub'],
            'Folk': ['folk', 'acoustic', 'singer-songwriter', 'indie folk'],
            'Metal': ['metal', 'heavy metal', 'death metal', 'black metal', 'thrash metal', 'metalcore'],
            'Indie': ['indie', 'indie pop', 'indie rock', 'lo-fi', 'lofi'],
        }

        genre_map = {}  # track -> genre

        for t in track_names:
            info = meta.get(t, {})
            yt_url = info.get('youtube_url', '')
            if not yt_url:
                continue
            try:
                result = subprocess.run(
                    [YTDLP, '--print', '%(tags)s\t%(description)s\t%(categories)s',
                     '--no-download', yt_url],
                    capture_output=True, text=True, timeout=10, cwd=DIR
                )
                if result.returncode == 0:
                    text = result.stdout.lower()
                    for genre, keywords in GENRE_KEYWORDS.items():
                        if any(kw in text for kw in keywords):
                            genre_map[t] = genre
                            break
            except Exception:
                pass

        # For tracks without YouTube URLs, try matching by filename keywords
        for t in track_names:
            if t in genre_map:
                continue
            name_lower = t.lower()
            for genre, keywords in GENRE_KEYWORDS.items():
                if any(kw in name_lower for kw in keywords):
                    genre_map[t] = genre
                    break

        # Build suggested playlists
        suggestions = {}
        for t, genre in genre_map.items():
            suggestions.setdefault(genre, []).append(t)

        # Collect unclassified
        unclassified = [t for t in track_names if t not in genre_map]
        if unclassified:
            suggestions['Other'] = unclassified

        # Only return genres with at least 2 tracks
        suggestions = {g: ts for g, ts in suggestions.items() if len(ts) >= 2}

        print(f"   Classified {len(genre_map)}/{len(track_names)} tracks into {len(suggestions)} genres")
        for g, ts in suggestions.items():
            print(f"     • {g}: {len(ts)} tracks")
        self._json(200, {'suggestions': suggestions})

    def _handle_upload(self):
        content_type = self.headers.get('Content-Type', '')
        if 'multipart/form-data' not in content_type:
            self._json(400, {'error': 'Expected multipart/form-data'})
            return

        boundary = content_type.split('boundary=')[1].encode()
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length)

        playlist_name = None
        saved_files = []

        parts = body.split(b'--' + boundary)
        for part in parts:
            if b'filename="' not in part:
                if b'name="playlist"' in part:
                    playlist_name = part.split(b'\r\n\r\n', 1)[1].strip(b'\r\n- ').decode()
                continue

            header, file_data = part.split(b'\r\n\r\n', 1)
            file_data = file_data.rstrip(b'\r\n--')
            header_str = header.decode('utf-8', errors='replace')

            filename_match = re.search(r'filename="([^"]+)"', header_str)
            if not filename_match:
                continue
            raw_name = filename_match.group(1)
            filename = os.path.basename(raw_name)
            if not filename.lower().endswith('.mp3'):
                continue

            filepath = os.path.join(DIR, filename)
            if not os.path.exists(filepath):
                with open(filepath, 'wb') as f:
                    f.write(file_data)
                print(f"   📁 Uploaded: {filename} ({len(file_data)} bytes)")
                saved_files.append(filename)
            else:
                print(f"   ⚠ Skipped (exists): {filename}")
                saved_files.append(filename)

        if playlist_name and saved_files:
            playlists = load_playlists()
            existing = playlists.get(playlist_name, [])
            for f in saved_files:
                if f not in existing:
                    existing.append(f)
            playlists[playlist_name] = existing
            save_playlists(playlists)
            print(f"   📋 Created playlist: {playlist_name} ({len(saved_files)} tracks)")

        rebuild_filelist()
        print(f"   ✅ Upload complete: {len(saved_files)} file(s)")
        self._json(200, {'ok': True, 'added': len(saved_files), 'tracks': saved_files, 'playlist': playlist_name})

    def _serve_zip(self):
        files = sorted(f for f in os.listdir(DIR) if f.endswith('.mp3'))
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w', zipfile.ZIP_STORED) as zf:
            for f in files:
                zf.write(os.path.join(DIR, f), f)
        data = buf.getvalue()
        self.send_response(200)
        self.send_header('Content-Type', 'application/zip')
        self.send_header('Content-Disposition', 'attachment; filename="playlist.zip"')
        self.send_header('Content-Length', len(data))
        self.end_headers()
        self.wfile.write(data)

    def _serve_export_zip(self):
        print("\n📦 Exporting all music and playlists...")
        files = sorted(f for f in os.listdir(DIR) if f.endswith('.mp3'))
        playlists = load_playlists()
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w', zipfile.ZIP_STORED) as zf:
            for f in files:
                zf.write(os.path.join(DIR, f), f'All Tracks/{f}')
            for pl_name, pl_tracks in playlists.items():
                safe_name = re.sub(r'[<>:"/\\|?*]', '_', pl_name)
                for t in pl_tracks:
                    src = os.path.join(DIR, t)
                    if os.path.exists(src):
                        zf.write(src, f'{safe_name}/{t}')
            meta = load_metadata()
            if meta:
                zf.writestr('metadata.json', json.dumps(meta, indent=2))
            if playlists:
                zf.writestr('playlists.json', json.dumps(playlists, indent=2))
        data = buf.getvalue()
        print(f"   ✅ Export ready: {len(files)} tracks, {len(playlists)} playlists, {len(data)//1024}KB")
        self.send_response(200)
        self.send_header('Content-Type', 'application/zip')
        self.send_header('Content-Disposition', 'attachment; filename="music-library-export.zip"')
        self.send_header('Content-Length', len(data))
        self.end_headers()
        self.wfile.write(data)

    def _json(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', len(body))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        msg = str(args[0]) if args else ''
        if '/api/' in msg or 'POST' in msg:
            super().log_message(fmt, *args)

if __name__ == '__main__':
    ip = get_local_ip()
    print(f"\n   Music Subscription Escape — Server running")
    print(f"   http://{ip}:{PORT}\n")
    class ThreadedServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True
    ThreadedServer(('', PORT), Handler).serve_forever()
