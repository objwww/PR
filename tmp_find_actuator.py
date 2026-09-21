# -*- coding: utf-8 -*-
import io, os

for base in [r"E:\kimiCode\control-app\src\main\resources", r"E:\kimiCode\deploy"]:
    for root, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if d not in {"target", "node_modules"}]
        for f in files:
            if f.endswith((".yml", ".yaml", ".properties", ".env", "env")):
                p = os.path.join(root, f)
                try:
                    t = io.open(p, encoding="utf-8", errors="replace").read()
                except OSError:
                    continue
                if "actuator" in t or "prometheus" in t or "management" in t:
                    print("=====", p)
                    for line in t.splitlines():
                        if any(k in line for k in ("actuator", "prometheus", "management", "metrics", "security")):
                            print("   ", line)
