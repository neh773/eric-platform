#!/bin/bash
# Container health check: GET /api/v1/health and require status UP.
port="${OIP_HTTP_PORT:-8088}"
exec 3<>"/dev/tcp/127.0.0.1/${port}" || exit 1
printf 'GET /api/v1/health HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 || exit 1
grep -q '"status":"UP"' <&3
