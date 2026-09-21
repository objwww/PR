# -*- coding: utf-8 -*-
import io, os

BASE = r"E:\kimiCode\control-app\src\test\java"
for root, dirs, files in os.walk(BASE):
    for name in files:
        if not name.endswith(".java"):
            continue
        text = io.open(os.path.join(root, name), encoding="utf-8", errors="replace").read()
        if "insertStartedIfAbsent" in text:
            idx = text.find("insertStartedIfAbsent")
            print("=====", name)
            print(text[max(0, idx - 1800):idx + 200])
            print()
            break
