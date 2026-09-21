#!/bin/sh
docker exec -i litellm-am3 python -c "import json,sys; json.load(sys.stdin); print('JSON-VALID')" < /tmp/p6env.json 2>&1 | tail -3
echo '---tail of json---'
tail -c 200 /tmp/p6env.json
