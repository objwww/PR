#!/bin/sh
echo '--- V87 中含 || 的行 ---'
grep -n '||' /opt/build/pr/control-app/src/main/resources/db/migration/V87__ev08_eval_review.sql
echo '--- V87 全部语句边界（前 5 个分号行号）---'
awk '/;/ {print NR": "$0}' /opt/build/pr/control-app/src/main/resources/db/migration/V87__ev08_eval_review.sql | head -5
echo '--- line 75 前后 12 行（含不可见字符检查）---'
sed -n '69,80p' /opt/build/pr/control-app/src/main/resources/db/migration/V87__ev08_eval_review.sql | cat -A | head -14
echo '--- 迁移目录里 60-88 文件清单 ---'
ls /opt/build/pr/control-app/src/main/resources/db/migration/ | awk -F__ '{v=substr($1,2)+0; if (v>=59 && v<=90) print}' | sort -t V -k 2 -n
