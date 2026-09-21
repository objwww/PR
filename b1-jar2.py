#!/usr/bin/env python3
# B1-1 辅证：宿主机侧扫描部署 jar 标记（python3 zipfile，只读）
import zipfile, re, sys

JAR = "/tmp/app-deployed.jar"
marks = {
    "ContextAssembler": "alert/application/agent/ContextAssembler.class",
    "LogQueryExecutor": "infrastructure/tool/LogQueryExecutor.class",
    "RunReconciler": None,          # 按名搜
    "PrimaryCheckpointCommitService": None,
    "RcaModelInputCapture": None,
}
z = zipfile.ZipFile(JAR)
names = z.namelist()

def find(prefix_needle):
    return [n for n in names if prefix_needle in n]

# 1) 关键类存在性
for label, exact in [("RunReconciler", "RunReconciler"),
                     ("PrimaryCheckpointCommitService(CL-01)", "PrimaryCheckpointCommitService"),
                     ("SkillCuratorService", "SkillCuratorService"),
                     ("ContextAssembler", "ContextAssembler.class"),
                     ("LogQueryExecutor", "LogQueryExecutor.class")]:
    hits = find(label.split("(")[0])
    print(f"{label}: {len(hits)} 命中 -> {[h.split('/')[-1] for h in hits[:3]]}")

# 2) ContextAssembler 内容标记（CL-03 投影 vs 旧 appendTopLevel）
ca = [n for n in names if n.endswith("agent/ContextAssembler.class")]
if ca:
    data = z.read(ca[0])
    for m in [b"projectLogs", b"projectMetrics", b"appendTopLevel", b"unknown_shape"]:
        print(f"ContextAssembler 含 {m.decode()}: {data.count(m)}")
else:
    print("ContextAssembler.class 未找到")

# 3) LogQueryExecutor 流式标记
lqe = [n for n in names if n.endswith("tool/LogQueryExecutor.class")]
if lqe:
    data = z.read(lqe[0])
    for m in [b"CountingInputStream", b"render"]:
        print(f"LogQueryExecutor 含 {m.decode()}: {data.count(m)}")
else:
    print("LogQueryExecutor.class 未找到")

# 4) 主清单 commit 信息（若有 Implementation-Version/Build-Commit）
mf = [n for n in names if n.endswith("META-INF/MANIFEST.MF")]
if mf:
    print("== MANIFEST ==")
    print(z.read(mf[0]).decode(errors="replace")[:800])
