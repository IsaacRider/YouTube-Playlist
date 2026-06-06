#!/usr/bin/env python3
import http.server
import io
import json
import os
import socket
import subprocess
import zipfile

PORT = 8888
DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(DIR)

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

def next_track_num():
    existing = sorted(f for f in os.listdir(DIR) if f.endswith('.mp3'))
    if existing:
        try:
            return int(existing[-1][:3]) + 1
        except ValueError:
            return len(existing) + 1
    return 1

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        if self.path == '/api/tracks':
            files = sorted(f for f in os.listdir(DIR) if f.endswith('.mp3'))
            sizes = {}
            for f in files:
                try:
                    sizes[f] = os.path.getsize(os.path.join(DIR, f))
                except OSError:
                    sizes[f] = 0
            self._json(200, {'tracks': files, 'sizes': sizes})
        elif self.path == '/download-all':
            self._serve_zip()
        else:
            super().do_GET()

    def do_POST(self):
        if self.path == '/api/download':
            length = int(self.headers.get('Content-Length', 0))
            body = json.loads(self.rfile.read(length))
            url = body.get('url', '').strip()
            if not url or ('youtube' not in url and 'youtu.be' not in url):
                self._json(400, {'error': 'Invalid YouTube URL'})
                return

            num = next_track_num()
            template = f"{num:03d} - %(title)s.%(ext)s"

            # Use --yes-playlist so playlist URLs download all videos
            try:
                result = subprocess.run(
                    ['yt-dlp', '-x', '--audio-format', 'mp3', '--audio-quality', '0',
                     '-o', template, '--no-overwrites', '--yes-playlist', url],
                    capture_output=True, text=True, timeout=600, cwd=DIR
                )

                files = rebuild_filelist()
                new_files = [f for f in files if f >= f"{num:03d}"]

                if result.returncode != 0 and not new_files:
                    self._json(500, {'error': result.stderr[:300]})
                    return

                self._json(200, {
                    'ok': True,
                    'added': len(new_files),
                    'tracks': new_files,
                    'total': len(files)
                })
            except subprocess.TimeoutExpired:
                files = rebuild_filelist()
                self._json(500, {'error': 'Download timed out (some tracks may have saved)'})
            except FileNotFoundError:
                self._json(500, {'error': 'yt-dlp not found. Install via: brew install yt-dlp'})
        else:
            self.send_error(404)

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
    print(f"\n🎵 Music Player Server")
    print(f"   Local:  http://localhost:{PORT}/player.html")
    print(f"   Phone:  http://{ip}:{PORT}/player.html")
    print(f"\n   Open the Phone URL on Android Chrome, then 'Add to Home Screen'\n")
    http.server.HTTPServer(('', PORT), Handler).serve_forever()
