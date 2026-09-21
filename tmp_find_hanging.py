# -*- coding: utf-8 -*-
import io, os, re

BASE = r"E:\kimiCode\control-app\src\test\java"
needles = ["findHangingStarted", "reclaimPendingOlderThan", "markHangingInvocationsUnknown"]
for root, dirs, files in os.walk(BASE):
    for name in files:
        if not name.endswith(".java"):
            continue
        path = os.path.join(root, name)
        text = io.open(path, encoding="utf-8", errors="replace").read()
        hits = [n for n in needles if n in text]
        if hits and "AlertInMemoryStores" not in name:
            print(name, "->", hits)
