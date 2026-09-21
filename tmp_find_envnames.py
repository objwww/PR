# -*- coding: utf-8 -*-
import io, os, re

BASE = r"E:\kimiCode\control-app\src\main\java"
pat = re.compile(r"[A-Z0-9_]*TOKEN[A-Z0-9_]*|[A-Z0-9_]*BEARER[A-Z0-9_]*|MachineLine\(")
for root, dirs, files in os.walk(BASE):
    for name in files:
        if not name.endswith(".java"):
            continue
        t = io.open(os.path.join(root, name), encoding="utf-8", errors="replace").read()
        if "MachineLine(" in t and "env" in t.lower():
            print("=====", name)
            for i, line in enumerate(t.splitlines()):
                if "MachineLine(" in line or "TOKEN" in line or "getProperty" in line or "env" in line.lower():
                    print("  %d: %s" % (i + 1, line.strip()))
