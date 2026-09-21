# -*- coding: utf-8 -*-
import io

path = r"E:\kimiCode\control-app\src\main\java\com\objwww\pr\control\alert\application\RunReconciler.java"
text = io.open(path, encoding="utf-8").read()
for needle in ["public int scanOnce", "refreshReconcileGauges", "cancelToQuiesce",
               "reconcileDecision"]:
    idx = text.find(needle)
    print("=====", needle, "at", idx)
    if idx >= 0:
        print(text[idx:idx + 2400])
        print()
