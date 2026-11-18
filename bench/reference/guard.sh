#!/bin/sh
# Thin wrapper: see reference_env.py
exec python3 "$(dirname "$0")/reference_env.py" guard "$@"
