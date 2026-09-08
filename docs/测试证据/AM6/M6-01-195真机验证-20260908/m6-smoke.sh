set -e
cd /opt/build/pr/deploy
. ./.env
P=${CONTROL_PORT:-8080}
NO_CODE=$(curl -s -o /tmp/m6-no.json -w '%{http_code}' http://127.0.0.1:$P/api/canary/status)
echo UNAUTH_CODE=$NO_CODE
cat /tmp/m6-no.json; echo
AUTH_CODE=$(curl -s -o /tmp/m6-auth.json -w '%{http_code}' -H "Authorization: Bearer $APP_RELEASE_API_BEARER" http://127.0.0.1:$P/api/canary/status)
echo AUTH_CODE=$AUTH_CODE
cat /tmp/m6-auth.json; echo
rm -f /tmp/m6-no.json /tmp/m6-auth.json
echo SMOKE-DONE
