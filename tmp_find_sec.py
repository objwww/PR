# -*- coding: utf-8 -*-
import io, os

BASE = r"E:\kimiCode\control-app\src\main\java"
for root, dirs, files in os.walk(BASE):
    for name in files:
        if not name.endswith(".java"):
            continue
        p = os.path.join(root, name)
        t = io.open(p, encoding="utf-8", errors="replace").read()
        if "actuator" in t and ("SecurityFilterChain" in t or "permitAll" in t or "requestMatchers" in t):
            print("=====", name)
            for i, line in enumerate(t.splitlines()):
                if "actuator" in line or "permitAll" in line:
                    print("  %d: %s" % (i + 1, line.strip()))
