#!/usr/bin/env sh
set -eu
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${JAVA_OPTS:=-Xms64m -Xmx512m}"
export JAVA_OPTS
if [ -n "${MPMC_DATA_DIR:-}" ]; then
    exec "$DIR/bin/mpmc" join --data-dir "$MPMC_DATA_DIR" "$@"
fi
exec "$DIR/bin/mpmc" join "$@"
