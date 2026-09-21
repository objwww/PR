# -*- coding: utf-8 -*-
import io, re
t = io.open(r'control-app/src/test/java/com/objwww/pr/control/it/PostgresCommandAtomicityIT.java',
            encoding='utf-8', errors='replace').read()
for l in t.splitlines():
    if 'Incident' in l and 'import' in l:
        print(l)
