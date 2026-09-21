import io
path = r'E:\kimiCode\docs\告警-BUGLOG.md'
bad = []
with io.open(path, encoding='utf-8') as f:
    for i, line in enumerate(f, 1):
        if line.startswith('| BA-') or line.startswith('|---'):
            cols = line.count('|')
            if cols != 9:
                bad.append((i, line[:20], cols))
print('rows with wrong column count:', bad if bad else 'NONE')
with io.open(path, encoding='utf-8') as f:
    rows = [l.split('|')[1].strip() for l in f if l.startswith('| BA-')]
print('total BA rows:', len(rows), '| last three:', rows[-3:])
