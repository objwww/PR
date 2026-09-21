# -*- coding: utf-8 -*-
import io

t = io.open(r"E:\kimiCode\control-app\src\main\resources\application.yml", encoding="utf-8").read()
i = t.find("management:")
print(t[i:i + 1400])
print("==== otlp mentions ====")
for n, line in enumerate(t.splitlines()):
    if "otlp" in line.lower() or "4317" in line or "4318" in line or "export" in line.lower():
        print(n + 1, line)
