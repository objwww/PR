# -*- coding: utf-8 -*-
import io

t = io.open(r"docs\告警-PROGRESS.md", encoding="utf-8").read()
print("len", len(t))
lines = t.splitlines()
print("total lines", len(lines))
# print head 60 lines and tail 40 lines
print("\n".join(lines[:55]))
print("==== TAIL ====")
print("\n".join(lines[-40:]))
