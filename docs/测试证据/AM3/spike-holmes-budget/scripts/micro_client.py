import json

import litellm

litellm.modify_params = True  # holmes sets this too (core/llm.py:679)


def call(label, **kw):
    base = dict(
        model="openai/deepseek-v3",
        api_key="dummy",
        base_url="http://spike-echo2:8000/v1",
        messages=[{"role": "user", "content": "hi"}],
        max_tokens=5,
    )
    base.update(kw)
    try:
        resp = litellm.completion(**base)
        print(label, "CALL_OK:", resp.choices[0].message.content)
    except Exception as e:  # noqa: BLE001 - evidence gathering
        print(label, "CALL_FAILED:", type(e).__name__, str(e)[:300])


call("K1(user+metadata+litellm_metadata)",
     user="spike-user-X",
     metadata={"spend_logs_metadata": {"run_id": "VIA-METADATA"}},
     litellm_metadata={"run_id": "VIA-LITELLM-METADATA"})
call("K2(extra_body)",
     extra_body={"litellm_metadata": {"run_id": "VIA-EXTRA-BODY"}})
call("K3(extra_headers+user)",
     user="spike-user-Y",
     extra_headers={"X-Run-Id": "run-VIA-EXTRA-HEADERS"})
print("MICRO_CLIENT_DONE")
