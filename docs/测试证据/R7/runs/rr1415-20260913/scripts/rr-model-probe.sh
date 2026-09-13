#!/bin/sh
# rr-model-probe.sh —— litellm 各模型配额面探针（master 直连，1 token ping）
docker exec litellm-am3 python3 -c "
import urllib.request,json,os
MK=os.environ['LITELLM_MASTER_KEY']
for m in ['glm-5','deepseek-v4-flash-0731','qwen-plus','qwen3-max-preview']:
    req=urllib.request.Request('http://127.0.0.1:4000/v1/chat/completions',
      data=json.dumps({'model':m,'messages':[{'role':'user','content':'ping'}],'max_tokens':4}).encode(),
      headers={'Authorization':'Bearer '+MK,'Content-Type':'application/json'})
    try:
        r=json.load(urllib.request.urlopen(req)); print(m,'OK model=',r.get('model'))
    except urllib.error.HTTPError as e:
        print(m,'HTTP',e.code,e.read().decode()[:90])
"
