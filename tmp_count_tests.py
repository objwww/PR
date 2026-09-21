# -*- coding: utf-8 -*-
import io, os, re

BASE = r"E:\kimiCode\control-app\target\surefire-reports"
total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for name in ["com.objwww.pr.control.alert.application.RunQueryServiceTest",
             "com.objwww.pr.control.alert.application.RunReconcilerTest",
             "com.objwww.pr.control.alert.application.RcaWorkerTest",
             "com.objwww.pr.control.infrastructure.observability.AlertMetricsLabelAllowlistTest"]:
    path = os.path.join(BASE, "TEST-%s.xml" % name)
    if not os.path.exists(path):
        print("missing", name)
        continue
    head = io.open(path, encoding="utf-8", errors="replace").read(2000)
    m = re.search(r'<testsuite[^>]*tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"[^>]*skipped="(\d+)"', head)
    if not m:
        m2 = re.findall(r'(tests|failures|errors|skipped)="(\d+)"', head)
        vals = {k: int(v) for k, v in m2[:8]}
        print(name.split(".")[-1], vals)
        continue
    t, f, e, s = (int(x) for x in m.groups())
    total["tests"] += t; total["failures"] += f; total["errors"] += e; total["skipped"] += s
    print(name.split(".")[-1], "tests=%d failures=%d errors=%d skipped=%d" % (t, f, e, s))
print("TOTAL", total)
