# -*- coding: utf-8 -*-
import subprocess
r = subprocess.run('npm.cmd run build', capture_output=True, text=True,
                   encoding='utf-8', errors='replace', shell=True, cwd='alert-web')
t = (r.stdout or '') + (r.stderr or '')
import re
print('rc', r.returncode)
for l in t.splitlines():
    if 'built in' in l or 'error' in l.lower():
        print(l.strip()[:150])
big = [l.strip() for l in t.splitlines() if re.search(r'kB.*gzip', l)]
big.sort(key=lambda s: -float(re.search(r'([\d.]+) kB', s).group(1)) if re.search(r'([\d.]+) kB \| gzip', s) or re.search(r'gzip:\s*[\d.]+', s) else 0)
for b in big[:10]:
    print(b[:160])
