# -*- coding: utf-8 -*-
import io, re

t = io.open(r"E:\kimiCode\deploy\docker-compose.yml", encoding="utf-8").read()
for i, line in enumerate(t.splitlines()):
    if re.search(r"OTLP|MANAGEMENT|MICROMETER|9465|4317|4318", line, re.I):
        print(i + 1, line.rstrip())
