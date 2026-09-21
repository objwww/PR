import io
with io.open(r'E:\kimiCode\docs\告警-PROGRESS.md', 'r', encoding='utf-8') as f:
    lines = f.readlines()
print('total', len(lines))
last = lines[-1]
print('last-line-tail:', last[-60:])
