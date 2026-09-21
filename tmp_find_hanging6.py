# -*- coding: utf-8 -*-
import io

path = r"E:\kimiCode\control-app\src\test\java\com\objwww\pr\control\alert\support\AlertInMemoryStores.java"
text = io.open(path, encoding="utf-8").read()
i = text.find("class Investigations")
print("class at", i)
seg = text[i:i + 4000]
# print first helper-looking methods
j = seg.find("started(")
print(seg[:2400])
