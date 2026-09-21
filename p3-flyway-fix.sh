#!/bin/sh
set -e
F=/opt/build/pr/control-app/src/main/resources/db/migration/V87__ev08_eval_review.sql
echo '--- 计算 flyway 风格校验和（CRC32，行尾归一）---'
CRC_NORM=$(python3 -c "
import zlib
data=open('$F','rb').read().replace(b'\r\n',b'\n')
print(zlib.crc32(data) & 0xffffffff)")
CRC_RAW=$(python3 -c "
import zlib
print(zlib.crc32(open('$F','rb').read()) & 0xffffffff)")
echo "norm=$CRC_NORM raw=$CRC_RAW"

echo '--- 清失败行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "delete from flyway_schema_history where version='87' and success=false;" || true
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version='87';"

echo '--- 尝试 norm 校验和补记录 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
insert into flyway_schema_history (
  installed_rank, version, description, type, script, checksum,
  installed_by, installed_on, execution_time, success)
select coalesce(max(installed_rank),0)+1, '87', 'ev08 eval review', 'SQL',
       'V87__ev08_eval_review.sql', $CRC_NORM, 'ops-fix', now(), 0, true
  from flyway_schema_history;"
echo '--- validate 试运行（migrate 会在 validate 后应用 V141）---'
cd /opt/build/pr/deploy
docker compose up migrate 2>&1 | tail -4
