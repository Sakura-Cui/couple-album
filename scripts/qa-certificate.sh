#!/usr/bin/env bash
# Only the debug APK trusts this local test certificate. Release uses system trust.
set -eu
cd "$(dirname "$0")/.."
mkdir -p .tools/qa-tls android/app/src/debug/res/raw
if [ ! -f .tools/qa-tls/key.pem ]; then
  umask 077
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout .tools/qa-tls/key.pem -out .tools/qa-tls/cert.pem \
    -subj '/CN=Sakura Local QA' \
    -addext 'subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost' \
    -addext 'basicConstraints=critical,CA:TRUE' \
    -addext 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign' 2>/dev/null
fi
cp .tools/qa-tls/cert.pem android/app/src/debug/res/raw/qa_certificate.pem
