import io, re

p = r"E:\kimiCode\control-app\src\main\java\com\objwww\pr\control\alert\application\RcaWorker.java"
for i, l in enumerate(io.open(p, encoding="utf-8").read().splitlines(), 1):
    if re.search(r"UNKNOWN|reclaimPending|markHanging|悬挂|recoverExpired\(", l):
        print(f"{i}: {l.strip()[:150]}")
