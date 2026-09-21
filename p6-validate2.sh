#!/bin/sh
docker cp /tmp/p6env.json litellm-am3:/tmp/p6env.json 2>&1
docker exec litellm-am3 sh -c "python -c \"import json; json.load(open('/tmp/p6env.json')); print('JSON-VALID')\"" 2>&1 | tail -3
