# -*- coding: utf-8 -*-
import io, os
for m, c in [(r'control-app/target/surefire-reports/com.objwww.pr.control.alert.application.agent.DelegationReceiptServiceTest.txt','DelegationReceiptServiceTest'),
             (r'control-app/target/surefire-reports/com.objwww.pr.control.alert.application.agent.SingleToolRoleRunnerChildResultTest.txt','SingleToolRoleRunnerChildResultTest'),
             (r'control-app/target/surefire-reports/com.objwww.pr.control.infrastructure.nativeexec.R7PrimaryModeExecutorTest.txt','R7PrimaryModeExecutorTest'),
             (r'control-app/target/surefire-reports/com.objwww.pr.control.infrastructure.nativeexec.NativeInvestigationExecutorTest.txt','NativeInvestigationExecutorTest')]:
    if os.path.exists(m):
        t = io.open(m, encoding='utf-8', errors='replace').read()
        for l in t.splitlines():
            if l.startswith('Tests run'):
                print(c, '::', l[:100])
                if 'Failures: 0, Errors: 0' not in l:
                    print(t[:1600])
    else:
        print('MISSING', c)
lg = io.open(r'tmp_rv4_test.log', encoding='utf-8', errors='replace').read()
print('LOGTAIL:', lg[-900:])
