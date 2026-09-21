import io

src = io.open(r"E:\kimiCode\docs\告警-看门狗与取消传播-代码复核及修复技术方案-v2.md",
              encoding="utf-8").read().splitlines()
io.open(r"E:\kimiCode\wc-doc-slice.txt", "w", encoding="utf-8").write(
    "\n".join("%d:%s" % (i, l) for i, l in enumerate(src[54:215], 55)))
print("wrote", len(src), "total lines; slice 55-215")
