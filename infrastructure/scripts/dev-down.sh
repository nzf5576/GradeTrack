#!/usr/bin/env bash
# Tears down GradeTrackDevStack entirely (VPC, RDS, Lambdas, API Gateway, S3, CloudFront)
# to stop AWS charges while the dev environment isn't in active use.
#
# This permanently deletes all data on the RDS instance - the stack is built with
# RemovalPolicy.DESTROY and zero backup retention, and no snapshot/dump is taken here.
# Run ./dev-up.sh to bring everything back; you'll need to re-seed dev data afterward.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env.deploy ]]; then
  echo ".env.deploy not found in infrastructure/ - create it with ALLOWED_IP, DB_PASSWORD, JWT_SECRET" >&2
  exit 1
fi
# shellcheck disable=SC1091
source .env.deploy
: "${ALLOWED_IP:?Set ALLOWED_IP in .env.deploy}"
: "${DB_PASSWORD:?Set DB_PASSWORD in .env.deploy}"
: "${JWT_SECRET:?Set JWT_SECRET in .env.deploy}"

echo "About to destroy GradeTrackDevStack - this deletes the RDS instance and all its data,"
echo "plus the API Gateway, Lambdas, and CloudFront/S3 frontend hosting."
echo

# cdk destroy still synthesizes the app first (to know what it's tearing down), so it
# needs the same context values as deploy even though nothing here is being created.
npx cdk destroy GradeTrackDevStack \
  -c "allowedIp=${ALLOWED_IP}" \
  -c "dbPassword=${DB_PASSWORD}" \
  -c "jwtSecret=${JWT_SECRET}"
