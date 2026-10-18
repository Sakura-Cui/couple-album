#!/usr/bin/env bash
# Read-only; no service mutations and no process environment/config secret output.
set -eu
uname -srmo
cat /etc/os-release
getconf _NPROCESSORS_ONLN
uptime
free -m
df -hT
ss -lnt
ss -lnu
docker version --format '{{.Server.Version}}'
docker compose version
docker ps --format '{{.Names}} {{.Image}} {{.Ports}}'
docker stats --no-stream --format '{{.Name}} CPU={{.CPUPerc}} MEM={{.MemUsage}}'
ps -eo comm,%cpu,%mem --sort=-%mem | head -15
