# -*- coding: utf-8 -*-
import io

path = r"E:\kimiCode\control-app\src\test\java\com\objwww\pr\control\alert\support\AlertInMemoryStores.java"
text = io.open(path, encoding="utf-8").read()
for needle in ["findHangingStarted", "reclaimPendingOlderThan"]:
    start = 0
    while True:
        idx = text.find(needle, start)
        if idx < 0:
            break
        print("=====", needle, "at", idx)
        print(text[max(0, idx - 700):idx + 500])
        print("...")
        start = idx + 1
