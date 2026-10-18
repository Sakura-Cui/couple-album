#!/bin/sh
set -eu
exec 9>/var/lock/sakura-ip-cert.lock
flock -n 9 || exit 0
docker run --rm --name sakura-ip-cert-renew \
  -v /opt/sakura-romance/ip-cert:/cert-state \
  -v /var/www/sakuyao-acme:/var/www/sakuyao-acme \
  sakura-certbot:5.8.0 renew --non-interactive --no-random-sleep-on-renew \
  --config-dir /cert-state --work-dir /cert-state/work --logs-dir /cert-state/logs
source=/opt/sakura-romance/ip-cert/live/shanghai-ip
openssl x509 -in "$source/cert.pem" -checkend 86400 -noout
openssl x509 -in "$source/cert.pem" -checkip 192.0.2.10 -noout
install -d -m 700 -o 10001 -g 10001 /opt/sakura-romance/tls/ip
install -m 600 -o 10001 -g 10001 "$source/fullchain.pem" /opt/sakura-romance/tls/ip/fullchain.pem
install -m 600 -o 10001 -g 10001 "$source/privkey.pem" /opt/sakura-romance/tls/ip/privkey.pem
docker exec sakura-romance-tls-1 nginx -t
docker exec sakura-romance-tls-1 nginx -s reload
