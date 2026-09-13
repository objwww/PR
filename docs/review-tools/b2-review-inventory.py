"""Build read-only BUGLOG index and hashes for the reviewed file set."""
from pathlib import Path
import collections
import hashlib
import json
import re

root = Path(__file__).resolve().parents[2]
rows = []
for line, text in enumerate((root / 'docs/告警-BUGLOG.md').read_text(encoding='utf-8').splitlines(), 1):
    match = re.match(r'^\|\s*(BA-\d+)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|', text)
    if match:
        key, status, title = match.groups()
        rows.append((key, line, status.replace('**', ''), title.replace('**', '')[:140]))
counts = collections.Counter(row[0] for row in rows)
dest = root / 'docs/告警-BUGLOG逐项复核索引-20260913.md'
header = ['# BUGLOG 逐项索引（2026-09-13）', '',
          '原始状态快照，不代表本轮逐项复验通过。行号用于区分重复编号；完整描述与证据仍以 BUGLOG 原行为准。', '',
          f'记录 {len(rows)} 条；唯一编号 {len(counts)} 个。重复编号：' + ', '.join(k for k,v in counts.items() if v > 1), '',
          '| 编号 | 原行号 | 原状态 | 问题摘要（截取） |', '|---|---|---|---|']
dest.write_text('\n'.join(header + ['| %s | %d | %s | %s |' % row for row in rows]) + '\n', encoding='utf-8')
paths = [
 'docs/告警-BUGLOG.md', 'docs/告警-CL-OP-OR完成台账-v1.md',
 'docs/告警-B2-RR21-28用例设计方案-v1.md',
 'docs/测试证据/R7/runs/b2-cl06-20260913/scripts/b2-cl06-verify.py',
 'docs/测试证据/R7/runs/b2-cl06-20260913/scripts/e2e-b2-cl06.sh',
 'docs/测试证据/R7/runs/b2-cl06-20260913/scripts/b2-cl06-retry.sh',
]
base='control-app/src/main/java/com/objwww/pr/control/'
paths += [base+p for p in [
 'alert/application/RunReconciler.java', 'alert/application/CommandService.java',
 'alert/application/tool/ToolGateway.java', 'alert/application/tool/InFlightToolCancels.java',
 'alert/application/agent/PrimaryGatewayToolPort.java',
 'alert/application/agent/PrimaryClaimAdmission.java', 'alert/application/agent/PrimaryFinalClaimProjector.java',
 'infrastructure/tool/LokiAggregateExecutor.java']]
manifest={p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in paths}
(root/'docs/b2-review-source-hashes.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'records':len(rows),'unique_ids':len(counts),'duplicate_ids':[k for k,v in counts.items() if v>1]},ensure_ascii=False))
