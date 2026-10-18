#!/usr/bin/env bash
# Signing material must be backed up privately; it is required for future upgrades.
set -eu
: "${JAVA_HOME:?Set JAVA_HOME to your JDK}"
target="${1:?Provide a private directory outside this repository}"
if [ -e "$target" ]; then
  echo 'Refusing to overwrite an existing signing directory.' >&2
  exit 1
fi
umask 077
mkdir -p "$target"
target="$(cd "$target" && pwd)"
openssl rand -base64 36 > "$target/password"
"$JAVA_HOME/bin/keytool" -genkeypair -alias sakura-romance -keyalg RSA -keysize 3072 \
  -validity 10000 -keystore "$target/release.jks" -storetype JKS \
  -storepass:file "$target/password" -keypass:file "$target/password" \
  -dname 'CN=Sakura Romance, OU=Private App, O=Sakura Romance, C=CN' >/dev/null 2>&1
{
  printf 'storeFile=%s/release.jks\nkeyAlias=sakura-romance\n' "$target"
  printf 'storePassword='; cat "$target/password"
  printf 'keyPassword='; cat "$target/password"
} > "$target/signing.properties"
chmod 600 "$target"/*
echo "Signing material created in $target (credentials not printed)."
