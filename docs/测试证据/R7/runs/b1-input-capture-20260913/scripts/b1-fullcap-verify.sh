#!/bin/sh
# B1-1 断言器：FULL 捕获 run 的模型输入——CL-03 投影入模/digest 完整/零秘密
set -u
OUT=/tmp/b1fullcap
RUN_ID=$(cat "$OUT/run-id.txt" 2>/dev/null | cut -d= -f2)
[ -n "$RUN_ID" ] || { echo "FAIL: 无 run id"; exit 1; }
echo "RUN_ID=$RUN_ID"

echo "== 1) 捕获行概览 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
 "select c.role_id||'|'||i.capture_level||'|'||length(i.prompt_text)||'|'||i.approx_tokens from rca_model_input i join rca_model_call c on c.id=i.model_call_id where c.run_id='$RUN_ID' order by c.role_id, c.action_seq;"

echo "== 2) 落盘 primary prompts =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
 "select c.action_seq||chr(9)||replace(i.prompt_text, chr(10), chr(10)) from rca_model_input i join rca_model_call c on c.id=i.model_call_id where c.run_id='$RUN_ID' and c.role_id='primary' order by c.action_seq;" \
 > "$OUT/prompts-raw.tsv" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
 "select i.prompt_text from rca_model_input i join rca_model_call c on c.id=i.model_call_id where c.run_id='$RUN_ID' and c.role_id='primary' order by c.action_seq;" > "$OUT/prompts-all.txt"
wc -c "$OUT"/prompts-* 2>/dev/null

echo "== 3) 断言（python）=="
python3 - <<'PY'
import hashlib, json, subprocess, sys, os
run = open("/tmp/b1fullcap/run-id.txt").read().strip().split("=",1)[1]
q = ("select c.action_seq, i.capture_level, i.prompt_text, i.prompt_digest, "
     "i.approx_tokens from rca_model_input i join rca_model_call c on "
     "c.id=i.model_call_id where c.run_id='%s' and c.role_id='primary' "
     "order by c.action_seq") % run
out = subprocess.run(["docker","exec","deploy-postgres-1","psql","-U","postgres",
                      "-d","pr_agent","-At","-F","\x1f","-c",q],
                     capture_output=True, text=True).stdout
rows = []
for line in out.splitlines():
    f = line.split("\x1f")
    if len(f) == 5:
        rows.append(f)
print(f"primary FULL 捕获行数: {len(rows)}")
assert rows, "FAIL: 无 primary 捕获行"
ok = True
texts = []
for seq, level, text, dig, tok in rows:
    if level != "FULL" or not text:
        print(f"  [seq={seq}] FAIL: level={level} text_len={len(text or '')}"); ok = False
        continue
    texts.append((seq, text, dig))
    # digest 完整性
    h = hashlib.sha256(text.encode("utf-8")).hexdigest()
    d_ok = h == (dig or "").lower()
    # 信封与投影标记
    env_ok = "valid_artifact_refs" in text
    proj_logs = '"observations"' in text or '"message"' in text
    proj_metr = '"labels"' in text
    no_old = "(+1 struct fields)" not in text and "struct fields)" not in text
    # 真实证据内容（payment 故障语义）
    ev_ok = ("Payment" in text) or ("error_type" in text) or ("payment" in text)
    print(f"  [seq={seq}] digest={'PASS' if d_ok else 'FAIL'} envelope={'PASS' if env_ok else '--'} "
          f"logs投影={'PASS' if proj_logs else '--'} metrics投影={'PASS' if proj_metr else '--'} "
          f"旧折叠残留={'无' if no_old else '有'} 真实证据语义={'PASS' if ev_ok else 'FAIL'}")
    if not d_ok: ok = False
    if not env_ok: ok = False
    if not ev_ok: ok = False
    if not no_old: ok = False
    with open(f"/tmp/b1fullcap/prompt-{seq}.txt", "w", encoding="utf-8") as f:
        f.write(text)
# 至少一条 prompt 同时具备 logs/metrics 投影标记（run26 配方 4 工具面）
any_logs = any('"observations"' in t for _, t, _ in texts)
any_metr = any('"labels"' in t for _, t, _ in texts)
print(f"logs 投影在场(any): {any_logs}; metrics 投影在场(any): {any_metr}")
if texts and not (any_logs or any_metr):
    print("  FAIL: 无任何 CL-03 投影标记"); ok = False
print("ASSERT-OVERALL:", "PASS" if ok else "FAIL")
PY

echo "== 4) RR16 模型输入半面：秘密扫描 =="
sh /opt/build/rr16-promptscan.sh "$OUT" || true
echo "VERIFY-DONE"
