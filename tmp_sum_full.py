# -*- coding: utf-8 -*-
import io, os, re

BASE = r"E:\kimiCode\control-app\target\surefire-reports"
t = f = e = s = 0
for name in os.listdir(BASE):
    if not (name.startswith("TEST-") and name.endswith(".xml")):
        continue
    head = io.open(os.path.join(BASE, name), encoding="utf-8", errors="replace").read(2000)
    vals = dict(re.findall(r'(tests|failures|errors|skipped)="(\d+)"', head))
    t += int(vals.get("tests", 0)); f += int(vals.get("failures", 0))
    e += int(vals.get("errors", 0)); s += int(vals.get("skipped", 0))
print("tests=%d failures=%d errors=%d skipped=%d" % (t, f, e, s))
