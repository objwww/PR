import sys

def murmur3_32(data):
    h = 0
    n = len(data) // 4
    for i in range(n):
        j = i * 4
        k = int.from_bytes(data[j:j+4], 'little')
        k = (k * 0xcc9e2d51) & 0xFFFFFFFF
        k = ((k << 15) | (k >> 17)) & 0xFFFFFFFF
        k = (k * 0x1b873593) & 0xFFFFFFFF
        h ^= k
        h = ((h << 13) | (h >> 19)) & 0xFFFFFFFF
        h = (h * 5 + 0xe6546b64) & 0xFFFFFFFF
    k = 0
    tail = n * 4
    r = len(data) & 3
    if r == 3:
        k ^= data[tail + 2] << 16
    if r >= 2:
        k ^= data[tail + 1] << 8
    if r >= 1:
        k ^= data[tail]
        k = (k * 0xcc9e2d51) & 0xFFFFFFFF
        k = ((k << 15) | (k >> 17)) & 0xFFFFFFFF
        k = (k * 0x1b873593) & 0xFFFFFFFF
        h ^= k
    h ^= len(data)
    h ^= h >> 16
    h = (h * 0x85ebca6b) & 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 0xc2b2ae35) & 0xFFFFFFFF
    h ^= h >> 16
    return h

def bucket(key, total=100):
    norm = key.strip().lower()
    return (murmur3_32(norm.encode('utf-8')) * total) >> 32

if __name__ == '__main__':
    assert murmur3_32(b'') == 0, '参考向量 hash("")=0'
    assert murmur3_32(b'hello') == 613153351, '参考向量 hash("hello")=613153351'
    bad = 0
    checked = 0
    for line in open(sys.argv[1], encoding='utf-8'):
        line = line.strip()
        if not line:
            continue
        key, b, dec = line.rsplit('|', 2)  # stickiness_key 内嵌 '|'（incidentKey 形），从右起拆
        expect = bucket(key)
        ok = (str(expect) == b)
        checked += 1
        print(('OK   ' if ok else 'FAIL ') + 'db_bucket=' + b
              + ' recomputed=' + str(expect) + ' decision=' + dec + ' key=' + key)
        if not ok:
            bad += 1
    print('SUMMARY checked=%d bad=%d' % (checked, bad))
    sys.exit(1 if bad else 0)
