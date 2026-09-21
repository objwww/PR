#!/bin/sh
cd /opt/build/pr/control-app/target/surefire-reports
for f in com.objwww.pr.control.it.PostgresCommandAtomicityIT.txt \
         com.objwww.pr.control.it.PostgresRunReconcilerFairnessIT.txt \
         com.objwww.pr.control.it.PostgresCheckpointLockOrderIT.txt; do
  echo "===== $f"
  sed -n '3,80p' "$f"
done
exit 0
