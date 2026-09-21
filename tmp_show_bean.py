# -*- coding: utf-8 -*-
import io
path = r"E:\kimiCode\control-app\src\main\java\com\objwww\pr\control\infrastructure\config\AlertFlowConfig.java"
lines = io.open(path, encoding="utf-8").read().splitlines()
start = None
for i, l in enumerate(lines):
    if "RcaWorker rcaWorker(" in l:
        start = i
print("bean at line", (start + 1) if start is not None else None)
if start is not None:
    print("\n".join(lines[start:start + 60]))
print("---- AlertMetrics lines:")
for l in lines:
    if "AlertMetrics" in l:
        print(l.strip())
