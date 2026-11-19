#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -e .env ]]; then
    db_password="$(openssl rand -hex 24)"
    demo_password="$(openssl rand -hex 24)"
    (
        umask 077
        set -o noclobber
        printf 'OMS_DB_PASSWORD=%s\nOMS_DEMO_PASSWORD=%s\n' "$db_password" "$demo_password" > .env
    )
fi

db_password="${OMS_DB_PASSWORD:-}"
demo_password="${OMS_DEMO_PASSWORD:-}"
set -a
source ./.env
set +a
export OMS_DB_PASSWORD="${db_password:-${OMS_DB_PASSWORD:-}}"
export OMS_DEMO_PASSWORD="${demo_password:-${OMS_DEMO_PASSWORD:-}}"

if [[ "${1:-}" == "--docker" ]]; then
    exec docker compose --profile app up --build
fi

docker compose up -d --wait db
npm --prefix frontend ci
npm --prefix frontend run build
mvn -B -f backend/pom.xml package

export OMS_DB_URL="${OMS_DB_URL:-jdbc:postgresql://127.0.0.1:${OMS_DB_PORT:-5432}/omsweb}"
exec java -jar backend/target/omsweb-payment-1.0.0.jar
