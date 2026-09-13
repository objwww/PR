#!/bin/sh
# rr-key-validate.sh —— litellm 虚键可用性探针：master 直连/带models虚键/无限制虚键
docker exec litellm-am3 python3 -c "
import urllib.request,json,os
MK=os.environ['LITELLM_MASTER_KEY']
def call(path,payload,method='POST',key=None):
    req=urllib.request.Request('http://127.0.0.1:4000'+path,
      data=json.dumps(payload).encode() if payload is not None else None,
      headers={'Authorization':'Bearer '+(key or MK),'Content-Type':'application/json'},method=method)
    try: return json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e: return {'__status':e.code,'body':e.read().decode()[:200]}
def chat(key,model='qwen3-max-preview'):
    r=call('/v1/chat/completions',{'model':model,'messages':[{'role':'user','content':'ping'}],'max_tokens':4},key=key)
    if '__status' in r: return 'HTTP %s: %s' % (r['__status'], r['body'][:120])
    return 'OK model=%s usage=%s' % (r.get('model'), bool(r.get('usage')))
import time
sts=int(time.time())
print('== 1) master 直连（模型面基线）==')
print('master:', chat(MK))
k1=call('/key/generate',{'key_alias':'rr14dbg-m-'+str(sts),'models':['qwen3-max-preview']})['key']
print('== 2) 带 models 限制虚键 ==')
print('models-key:', chat(k1))
info=call('/key/info',{'keys':[k1]})
ki=(info.get('key_info') or [{}])
if ki: print('   key_info: team_id=%s user_id=%s models=%s blocked=%s' % (ki[0].get('team_id'),ki[0].get('user_id'),ki[0].get('models'),ki[0].get('blocked')))
k2=call('/key/generate',{'key_alias':'rr14dbg-u-'+str(sts)})['key']
print('== 3) 无限制虚键 ==')
print('unrestricted-key:', chat(k2))
print('== 清理探针键 ==')
print(call('/key/delete',{'keys':[k1,k2]}).get('error','ok'))
"
