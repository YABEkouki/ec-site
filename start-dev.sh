#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
    echo "ERROR: ${ENV_FILE} が見つかりません。" >&2
    echo ".env.example を参考に .env を作成してください。" >&2
    exit 1
fi

set -a
source "${ENV_FILE}"
set +a

exec "${SCRIPT_DIR}/mvnw" spring-boot:run
