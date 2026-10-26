#!/bin/bash
# Send the commute route + TomTom key to the TV (see tools/commute-setup.py).
cd "$(dirname "$0")" && exec python3 -I tools/commute-setup.py
