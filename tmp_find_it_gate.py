# -*- coding: utf-8 -*-
import io

t = io.open(r"E:\kimiCode\control-app\src\test\java\com\objwww\pr\control\it\PostgresRunReconcilerFairnessIT.java", encoding="utf-8").read()
for n, line in enumerate(t.splitlines()):
    if any(k in line for k in ("EnabledIf", "@Tag", "controlDataSource", "static", "BeforeAll", "class Postgres")):
        print(n + 1, line.rstrip()[:150])
