#!/usr/bin/env bash
# Brings GradeTrackDevStack back up after ./dev-down.sh: builds the backend jars and
# frontend, deploys the stack, points the frontend at the new API, and runs migrations.
#
# A destroy/recreate cycle gets a brand new API Gateway URL and CloudFront domain every
# time (unlike a normal in-place `cdk deploy`, these aren't stable identities across a
# full teardown). The frontend bakes VITE_API_URL in at build time, so this runs in two
# passes: deploy once to learn the new API URL, rebuild the frontend against it, then
# redeploy so the S3 bucket gets the build with the correct URL baked in.
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

CDK_ARGS=(GradeTrackDevStack
  -c "allowedIp=${ALLOWED_IP}"
  -c "dbPassword=${DB_PASSWORD}"
  -c "jwtSecret=${JWT_SECRET}"
  --outputs-file cdk-outputs.json)

echo "==> Building backend Lambda jars"
mvn -f ../backend/pom.xml -q package

echo "==> Building frontend (pass 1 - URL gets corrected after deploy)"
( cd ../frontend && npm run build )

echo "==> Deploying GradeTrackDevStack (pass 1)"
npx cdk deploy "${CDK_ARGS[@]}"

API_URL=$(jq -r '.GradeTrackDevStack.ApiUrl' cdk-outputs.json | sed 's:/$::')
DB_HOST=$(jq -r '.GradeTrackDevStack.DbEndpoint' cdk-outputs.json)
FRONTEND_URL=$(jq -r '.GradeTrackDevStack.FrontendUrl' cdk-outputs.json)

echo "==> Pointing frontend at the new API URL: ${API_URL}"
echo "VITE_API_URL=${API_URL}" > ../frontend/.env

echo "==> Rebuilding frontend with correct API URL"
( cd ../frontend && npm run build )

echo "==> Deploying GradeTrackDevStack (pass 2 - uploads corrected frontend build)"
npx cdk deploy "${CDK_ARGS[@]}"

echo "==> Running Flyway migrations against ${DB_HOST}"
mvn -f ../db/pom.xml -q flyway:migrate \
  -Ddb.url="jdbc:postgresql://${DB_HOST}:5432/gradetrack?sslmode=require" \
  -Ddb.user=gradetrack \
  -Ddb.password="${DB_PASSWORD}"

cat <<EOF

Stack is up.
  Frontend:    ${FRONTEND_URL}
  API URL:     ${API_URL}
  DB endpoint: ${DB_HOST}

This is a fresh database - only the schema exists, no data. To recreate dev fixtures:
  1. Sign up dev-seed-admin@example.com / dev-seed-teacher@example.com / dev-seed-parent@example.com
     via POST ${API_URL}/auth/signup (all land as role=parent).
  2. Promote the admin account to role='admin':
     mvn -f ../db/pom.xml sql:execute@run-sql \\
       -Ddb.url="jdbc:postgresql://${DB_HOST}:5432/gradetrack?sslmode=require" \\
       -Ddb.user=gradetrack -Ddb.password="${DB_PASSWORD}" \\
       -DsqlCommand="UPDATE user_account SET role='admin' WHERE email='dev-seed-admin@example.com'"
  3. Log in as that admin and link dev-seed-teacher's account to the Dana Osei teacher
     record (id 00000000-0000-0000-0000-000000000005) via the "Give a teacher a login"
     admin console flow (POST /admin/teachers/{id}/link-account).
  4. Seed the Lincoln Elementary/Alex Rivera fixture data, using dev-seed-parent's user id:
     mvn -f ../db/pom.xml generate-resources sql:execute@seed \\
       -Ddb.url="jdbc:postgresql://${DB_HOST}:5432/gradetrack?sslmode=require" \\
       -Ddb.user=gradetrack -Ddb.password="${DB_PASSWORD}" \\
       -DdevParentUserId=<dev-seed-parent's user id from step 1>

Any ad-hoc data from manual UI testing on the previous instance (e.g. schools created by
hand) is gone for good - it only ever lived in the destroyed database.
EOF
