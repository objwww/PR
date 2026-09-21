#!/bin/sh
grep -n -B2 -A2 'usage->' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.java | head -40
echo '=== md5 与修改时间:'
md5sum /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.java
ls -la --time-style=+%m-%d_%H:%M:%S /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.java
