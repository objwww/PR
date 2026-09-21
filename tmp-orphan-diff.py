import io

gitset = set()
with io.open(r"E:/kimiCode/tmp-git-files.txt", encoding="utf-8", errors="replace") as f:
    for line in f:
        gitset.add(line.rstrip("\r\n"))
srv = []
with io.open(r"E:/kimiCode/tmp-srv-files.txt", encoding="utf-8", errors="replace") as f:
    for line in f:
        srv.append(line.rstrip("\r\n"))
orphans = [x for x in srv if x not in gitset]
missing_on_srv = sorted(gitset - set(srv))
print("srv-files:", len(srv), "git-files:", len(gitset))
print("orphans(on-195, NOT in git):", len(orphans))
for x in orphans:
    print("  ORPHAN:", x)
print("in-git-but-absent-on-195:", len(missing_on_srv))
for x in missing_on_srv[:20]:
    print("  ABSENT:", x)
