# -*- coding: utf-8 -*-
import subprocess
r = subprocess.run('mvn -q -pl control-app test-compile', capture_output=True, text=True,
                   encoding='utf-8', errors='replace', shell=True)
print('rc', r.returncode)
t = (r.stdout or '') + (r.stderr or '')
if r.returncode != 0:
    print(t[-1500:])
else:
    print('test-compile OK')
