#!/bin/sh
cd /tmp/jx
grep -a -o 'select requested_model[^"]*' BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class | head -c 1200
echo
echo '=== 所有含 usage 的常量串:'
grep -a -o "[a-z()'>~-]*usage[a-z()'>~+ -]*" BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class | sort -u | head
