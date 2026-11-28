#!/usr/bin/env bash
# Creates .env.docker and secrets/ (idempotent: never overwrites existing values).
set -euo pipefail
cd "$(dirname "$0")/.."
rand() { openssl rand -hex 24; }
mkdir -p secrets
if [ ! -f .env.docker ]; then
  sed -e "s/^CI_DB_PASSWORD=.*/CI_DB_PASSWORD=$(rand)/" \
      -e "s/^LMS_DB_PASSWORD=.*/LMS_DB_PASSWORD=$(rand)/" .env.docker.example > .env.docker
  echo "created .env.docker"
fi
if [ ! -f secrets/jwt-private.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem 2>/dev/null
  openssl pkey -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem
  echo "created JWT key pair"
fi
if [ ! -f secrets/recovery-token.key ]; then
  openssl rand -base64 32 | tr -d '\n' > secrets/recovery-token.key
  echo "created recovery token key"
fi
# Files are 0644 so the non-root container user can read them; the directory restricts host access.
chmod 700 secrets; chmod 644 secrets/*; chmod 600 .env.docker
echo "Back up secrets/ together with the database volumes; losing it invalidates tokens and recovery links."
