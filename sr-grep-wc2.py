import io
import pathlib
import re

root = pathlib.Path(r"E:\kimiCode")
pats = {
    "callctx": re.compile(r"new (SingleToolEvidenceAgent\.)?CallContext\("),
    "invocation": re.compile(r"new ToolGateway\.ToolInvocation\(|new ToolInvocation\("),
    "visreason": re.compile(r"enum ToolModelVisibleReason"),
}
for sub in [r"control-app\src\main\java", r"control-app\src\test\java"]:
    for p in sorted((root / sub).rglob("*.java")):
        t = p.read_text(encoding="utf-8", errors="replace")
        for name, pat in pats.items():
            for m in pat.finditer(t):
                line = t.count("\n", 0, m.start()) + 1
                print(name, p.relative_to(root), line)
