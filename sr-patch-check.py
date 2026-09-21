import io
p = r'E:\kimiCode\control-app\src\test\java\com\objwww\pr\control\it\PostgresCheckpointLockOrderIT.java'
s = io.open(p, encoding='utf-8').read()
print('final int r count:', s.count('final int r'))
print('round refs remaining:', s.count('" + round') + s.count('" + round)'))
print('round %s remaining:', s.count('round %s'))
