# -*- coding: utf-8 -*-
import io

path = r"E:\kimiCode\control-app\src\test\java\com\objwww\pr\control\alert\support\AlertInMemoryStores.java"
text = io.open(path, encoding="utf-8").read()
# ToolLedger fake full body
i = text.find("public static final class ToolLedger")
print(text[i:i + 5200])
