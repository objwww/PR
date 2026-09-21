import io
p = r'E:\kimiCode\control-app\src\test\java\com\objwww\pr\control\it\PostgresCheckpointLockOrderIT.java'
s = io.open(p, encoding='utf-8').read()
s = s.replace('for (int round = 0; round < 10; round++) {\n            Seed seed',
              'for (int round = 0; round < 10; round++) {\n            final int r = round;\n            Seed seed')
s = s.replace('for (int round = 0; round < 6; round++) {\n            Seed seed',
              'for (int round = 0; round < 6; round++) {\n            final int r = round;\n            Seed seed')
s = s.replace('"digest-" + round', '"digest-" + r')
s = s.replace('"wc-t08-" + round', '"wc-t08-" + r')
s = s.replace('"DELEGATE-gap-" + round', '"DELEGATE-gap-" + r')
s = s.replace('round %s ', 'round %d ')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('final int r count:', s.count('final int r'))
print('round refs remaining:', s.count('" + round'))
print('round %s remaining:', s.count('round %s'))
