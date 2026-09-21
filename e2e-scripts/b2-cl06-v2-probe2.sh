#!/bin/sh
# b2-cl06-v2-probe2.sh —— 手动 verify2 三 run（witness 面预检）
mkdir -p /tmp/v2probe
for r in 64071b2e-a6d1-4317-861a-85f6b8a6cf9a 79f05157-ed04-48d9-9795-b626c63db5e8 b40089be-55df-4d3a-a3c9-74ab3172778b; do
  echo "=== run=$r"
  python3 /opt/build/b2tree/b2-cl06-verify2.py --run-id "$r" --out "/tmp/v2probe/$r"
  echo "exit=$?"
done
echo "=== witness faces ==="
grep -h -E 'witness|status' /tmp/v2probe/*/verify2-result.json | head -12
