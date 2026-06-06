#!/bin/bash
cd "$(dirname "$0")"
open http://localhost:8888/player.html
python3 server.py
