#!/bin/sh
# P3-3: 卷元数据交叉对账（daemon 元数据，零盘 IO）+ compose 面发现 + /root 与 /opt 一层勘察（低 IO）
OUT=/tmp/p3_meta.txt
: > "$OUT"
{
echo "===== [M1] dangling volumes metadata (CreatedAt/Labels) ====="
docker volume ls -qf dangling=true | while read v; do
  echo "--- $v"
  docker volume inspect "$v" --format 'created={{.CreatedAt}} labels={{json .Labels}}'
done
echo
echo "===== [M2] in-use volumes metadata ====="
for v in deploy_cas-data deploy_pg-data alert_litellm-logs 11851d574af737b711c69b29b68e764dea4c5de6c1dde0c9bf0d240900baa104 09ffdba56f7dc3921937a6d2e86bcb4130ddc7c937e766fbaf090366fd08bf0d 9e8630d0ec29055b87b8905ea0f0bc730416af903100e41d96f863d73713e34d; do
  echo "--- $v"
  docker volume inspect "$v" --format 'created={{.CreatedAt}} labels={{json .Labels}}'
done
echo
echo "===== [M3] compose files under /opt (maxdepth 5, light find) ====="
ionice -c 3 nice -n 19 find /opt -maxdepth 5 \( -name 'docker-compose*.yml' -o -name 'docker-compose*.yaml' -o -name 'compose*.yml' -o -name 'compose*.yaml' \) 2>/dev/null
echo
echo "===== [M4] grep hotel / m2repo refs in those compose files ====="
ionice -c 3 nice -n 19 find /opt -maxdepth 5 \( -name 'docker-compose*.yml' -o -name 'compose*.yml' \) 2>/dev/null | while read f; do
  if grep -l -e hotel -e m2repo "$f" >/dev/null 2>&1; then
    echo "HIT: $f"
    grep -n -e hotel -e m2repo "$f" | head -10
  fi
done
echo "(no HIT above = no compose decl found)"
echo
echo "===== [M5] /root one-level du ====="
ionice -c 3 nice -n 19 du -sh /root/*/ 2>/dev/null | sort -h
echo
echo "===== [M6] /root files >20M (maxdepth 1) ====="
ionice -c 3 nice -n 19 find /root -maxdepth 1 -type f -size +20M -printf '%s %p\n' 2>/dev/null | sort -n
echo
echo "===== [M7] /opt one-level du ====="
ionice -c 3 nice -n 19 du -sh /opt/* 2>/dev/null | sort -h
echo
echo "===== [M8] /var/lib/docker one-level du ====="
ionice -c 3 nice -n 19 du -sh /var/lib/docker/* 2>/dev/null | sort -h
} >> "$OUT" 2>&1
echo "P3_META_DONE"
