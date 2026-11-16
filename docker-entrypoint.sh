#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -eq 0 ]; then
    find /data ! -user school -exec chown school:school {} +
    exec setpriv --reuid=school --regid=school --init-groups --inh-caps=-all "$@"
fi

exec "$@"
