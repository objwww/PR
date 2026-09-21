# -*- coding: utf-8 -*-
# 计算未推送对象总字节数（origin/feat/a-batch-readfaces..HEAD）
import subprocess

names = subprocess.run(["git", "rev-list", "--objects",
                        "origin/feat/a-batch-readfaces..HEAD"],
                       capture_output=True).stdout.decode().splitlines()
oids = [ln.split()[0] for ln in names if ln.strip()]
inp = "\n".join(oids).encode()
out = subprocess.run(["git", "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)"],
                     input=inp, capture_output=True).stdout.decode()
total = 0
big = []
for ln in out.splitlines():
    parts = ln.split()
    if len(parts) == 3 and parts[1] == "blob":
        sz = int(parts[2])
        total += sz
        if sz > 8_000_000:
            big.append((sz, parts[0]))
print("objects:", len(oids), "total_bytes:", total, "=", round(total/1048576), "MiB")
for sz, oid in sorted(big, reverse=True)[:5]:
    print("  big:", round(sz/1048576, 1), "MiB", oid)
