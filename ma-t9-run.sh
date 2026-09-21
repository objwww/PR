#!/bin/bash
# T9 演练 B 编排器：S20→S22→S25→S21→S24 串行（chaos- 宽靶避免交叉注入）
source /tmp/ma-drill-lib.sh
echo "T9 START $(date -u +%FT%TZ)"
bash /tmp/ma-t9-s20.sh
bash /tmp/ma-t9-s22.sh
bash /tmp/ma-t9-s25.sh
bash /tmp/ma-t9-s21.sh
bash /tmp/ma-t9-s24.sh
echo "T9 DONE $(date -u +%FT%TZ)"
echo "======== S23 部分验证标注取证（M-a 只验规则在场，复合激活依赖 M-b）========"
curl -s 'http://127.0.0.1:9090/api/v1/rules' | grep -o '"name":"ArenaOrderSuccessRateLow"[^}]*"health":"[a-z]*"' | head -1
curl -s 'http://127.0.0.1:9090/api/v1/query?query=clamp_min(oa_orders_success_total%2C0)' | head -c 120
