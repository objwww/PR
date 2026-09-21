#!/usr/bin/env python3
# B4 构造点批量补参：new RcaRunOrchestrator(...) / new RcaWorker(...) 尾插 null
import io

BASE = r"E:\kimiCode\control-app\src\test\java"
TARGETS = {
    "new RcaRunOrchestrator(": ", null",
    "new RcaWorker(": ", null",
}
FILES = [
    "com/objwww/pr/control/alert/application/NativeEngineWiringTest.java",
    "com/objwww/pr/control/alert/application/PublicationWinnerGateTest.java",
    "com/objwww/pr/control/alert/application/RcaWorkerTest.java",
    "com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutorTest.java",
    "com/objwww/pr/control/infrastructure/nativeexec/R7PrimaryModeExecutorTest.java",
    "com/objwww/pr/control/it/AlertGenerationFenceIT.java",
    "com/objwww/pr/control/it/Am4E2E06FaultDrillIT.java",
    "com/objwww/pr/control/it/Am6NativeFullChainIT.java",
    "com/objwww/pr/control/it/ExA2LeaseCancelFenceIT.java",
]

def balance_insert(text, open_paren, insert_text):
    depth = 0
    i = open_paren
    while i < len(text):
        c = text[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[:i] + insert_text + text[i:]
        i += 1
    raise ValueError("unbalanced")

for rel in FILES:
    path = BASE + "\\" + rel.replace("/", "\\")
    with io.open(path, "r", encoding="utf-8") as f:
        text = f.read()
    changed = False
    for marker, insert_text in TARGETS.items():
        idx = 0
        while True:
            pos = text.find(marker, idx)
            if pos < 0:
                break
            open_paren = pos + len(marker) - 1
            text = balance_insert(text, open_paren, insert_text)
            changed = True
            idx = pos + len(marker)
    if changed:
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)
        print("PATCHED", rel)
    else:
        print("NO-CHANGE", rel)
