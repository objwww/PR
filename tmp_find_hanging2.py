# -*- coding: utf-8 -*-
import io

path = r"E:\kimiCode\control-app\src\test\java\com\objwww\pr\control\alert\application\AlertInMemoryStores.java"
text = io.open(path, encoding="utf-8").read()
for needle in ["findHangingStarted", "reclaimPendingOlderThan", "class ToolLedger",
               "countByRunAndState", "class Invocations", "class Investigations"]:
    idx = text.find(needle)
    print("=====", needle, "at", idx)
    if idx >= 0:
        print(text[idx:idx + 900])
        print()
