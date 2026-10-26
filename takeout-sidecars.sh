#!/bin/bash
# Collect Google Photos Takeout metadata files for the TV (see tools/takeout-sidecars.py).
exec python3 -I "$(dirname "$0")/tools/takeout-sidecars.py" "$@"
