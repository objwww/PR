#!/bin/sh
echo '--- 服务器 V87 line 70-85 ---'
sed -n '70,85p' /opt/build/pr/control-app/src/main/resources/db/migration/V87__ev08_eval_review.sql
echo '--- 服务器 V87 md5 ---'
md5sum /opt/build/pr/control-app/src/main/resources/db/migration/V87__ev08_eval_review.sql
echo '--- migrate 完整日志头 40 行 ---'
docker logs deploy-migrate-1 2>&1 | head -40
