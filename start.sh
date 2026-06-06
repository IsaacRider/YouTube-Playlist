#!/bin/bash
cd "$(dirname "$0")"
open -a Firefox http://localhost:8888/player.html
python3 server.py
