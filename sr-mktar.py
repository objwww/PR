import os, tarfile, sys

ROOT = r"E:\kimiCode"
OUT = r"E:\kimiCode\pr-sr.tar.gz"
# 只带 195 构建/部署所需：control-app + 其依赖模块 + 前端 + compose + 文档。
# var/research/backups 等本机研究目录与 .env（机器特定）不进包。
INCLUDE_TOP = {"pom.xml", "control-app", "shared-kernel", "alert-web", "deploy", "docs",
               "order-arena", "arena-chaos-admin", "notify-app", "duty-adapter"}
EXCLUDE_DIRS = {".git", "target", "node_modules", "dist", "build", ".idea"}
EXCLUDE_FILES = {"pr-sr.tar.gz", "sr-mktar.py"}
count = 0
with tarfile.open(OUT, "w:gz") as tf:
    for top in sorted(INCLUDE_TOP):
        full_top = os.path.join(ROOT, top)
        if os.path.isfile(full_top):
            tf.add(full_top, arcname=top, recursive=False)
            count += 1
            continue
        for dirpath, dirnames, filenames in os.walk(full_top):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
            for name in filenames:
                if name in EXCLUDE_FILES or name.endswith(".tar.gz"):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, ROOT).replace("\\", "/")
                try:
                    tf.add(full, arcname=rel, recursive=False)
                    count += 1
                except OSError as e:
                    print("skip", rel, e, file=sys.stderr)
print("files:", count)
