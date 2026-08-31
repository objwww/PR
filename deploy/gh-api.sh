#!/usr/bin/env bash
# ============================================================================
# gh-api.sh —— 用 GitHub App installation token 调真实 GitHub API 的最小助手
# 仅用于部署验证（DP-13 真实仓库闭环）；私钥不出服务器（keys/ 只读挂载）。
# 用法：
#   gh-api.sh GET    /repos/objwww/mall_R/pulls/1
#   gh-api.sh POST   /repos/objwww/mall_R/pulls '{"title":"...","draft":true,...}'
#   gh-api.sh PATCH  /repos/objwww/mall_R/pulls/1 '{"state":"closed"}'
#   gh-api.sh GRAPHQL '{"query":"mutation {...}"}'
# 环境：.env 需提供 GITHUB_APP_ID / GITHUB_INSTALLATION_ID（真实模式值）。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] && { set -a; . ./.env; set +a; }
APP_ID="${GITHUB_APP_ID:?缺 GITHUB_APP_ID}"
INST="${GITHUB_INSTALLATION_ID:?缺 GITHUB_INSTALLATION_ID}"
KEY="keys/github-app-key.pem"
[ -f "$KEY" ] || { echo "缺 $KEY" >&2; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
NOW=$(date +%s)
H=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
P=$(printf '{"iat":%d,"exp":%d,"iss":"%s"}' $((NOW - 60)) $((NOW + 540)) "$APP_ID" | b64url)
S=$(printf '%s.%s' "$H" "$P" | openssl dgst -sha256 -sign "$KEY" | b64url)
JWT="$H.$P.$S"

TOKEN=$(curl -sf -X POST \
    -H "Authorization: Bearer $JWT" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/app/installations/$INST/access_tokens" | jq -r '.token')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "installation token 铸造失败" >&2; exit 1; }

METHOD="$1"; PATH_="$2"; BODY="${3:-}"
if [ "$METHOD" = "GRAPHQL" ]; then
    curl -sf -X POST -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        https://api.github.com/graphql -d "$PATH_"
elif [ -n "$BODY" ]; then
    curl -sf -X "$METHOD" -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com$PATH_" -d "$BODY"
else
    curl -sf -X "$METHOD" -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com$PATH_"
fi
