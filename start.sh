#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

docker compose up -d --wait db
npm --prefix frontend ci
npm --prefix frontend run build
mvn -B -f backend/pom.xml package

export OMS_DB_URL="${OMS_DB_URL:-jdbc:postgresql://127.0.0.1:${OMS_DB_PORT:-5432}/omsweb}"
exec java -jar backend/target/omsweb-payment-1.0.0.jar
