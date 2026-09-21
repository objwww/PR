#!/usr/bin/env python3
# 修正 canary-bundle-publish2.sh：qualify 先于 activate（EN-02 资格门顺序）
p = '/opt/build/canary-bundle-publish2.sh'
s = open(p).read()
s = s.replace(
    'r7_activate "$NEW_DIGEST" "$RUNS"\necho "activated"\nr7_qualify "$NEW_DIGEST" "$RUNS" || true',
    'r7_qualify "$NEW_DIGEST" "$RUNS" || true\nr7_activate "$NEW_DIGEST" "$RUNS"\necho "activated"')
open(p, 'w').write(s)
print('reordered')
