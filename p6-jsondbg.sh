#!/bin/sh
cd /opt/build/pr
sha() { sha256sum "$1" | cut -d' ' -f1; }
PROMPT_DIGEST=$(sha control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java)
SPRING_JSON=$(printf '{"app":{"alert":{"eval":{"registry-path":"file:/eval/eval-scenarios.yml","prompt-digest":"%s","judge":{"base-url":"http://litellm-am3:4000","api-key":"sk-x"}},"eval":{"rounds":2}}}}' "$PROMPT_DIGEST")
echo "$SPRING_JSON" | head -c 300
echo ''
docker exec litellm-am3 python -c "import json,sys; json.loads(sys.argv[1]); print('JSON-VALID')" "$SPRING_JSON" 2>&1 | tail -2
