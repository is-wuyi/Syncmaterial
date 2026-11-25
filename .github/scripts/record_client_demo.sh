#!/bin/sh
set -eu

here=$(CDPATH= cd "$(dirname "$0")" && pwd)
exec python3 -I "$here/record_client_demo.py" "$@"
