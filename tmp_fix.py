# -*- coding: utf-8 -*-
import io
p = r'control-app/src/test/java/com/objwww/pr/control/it/PostgresDelegationReceiptConcurrencyIT.java'
t = io.open(p, encoding='utf-8').read()
# 还原我误删的 import 行为：改为正确路径 model.IncidentStatus
if 'import com.objwww.pr.control.alert.domain.model.IncidentStatus;' not in t:
    t = t.replace('import com.objwww.pr.control.alert.domain.model.Incident;',
                  'import com.objwww.pr.control.alert.domain.model.Incident;\nimport com.objwww.pr.control.alert.domain.model.IncidentStatus;')
    io.open(p, 'w', encoding='utf-8').write(t)
print('import ok')
