#!/bin/bash
# One-time Google Photos sign-in for the TV (see tools/google-signin.py).
cd "$(dirname "$0")" && exec python3 -I tools/google-signin.py
