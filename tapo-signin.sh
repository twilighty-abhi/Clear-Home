#!/bin/bash
# Send the Tapo login + plug names to the TV (see tools/tapo-signin.py).
cd "$(dirname "$0")" && exec python3 -I tools/tapo-signin.py
