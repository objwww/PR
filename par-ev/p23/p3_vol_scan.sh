#!/bin/sh
# P3-2: 逐卷勘察（只读：直读宿主 _data，禁 docker run；分批<=8 卷，批间 sleep 5，全程 ionice idle）
OUT=/tmp/p3_vol_survey.txt
: > "$OUT"
i=0
while read v; do
  [ -z "$v" ] && continue
  d="/var/lib/docker/volumes/$v/_data"
  {
  echo "===== VOL $v ====="
  if [ -d "$d" ]; then
    ionice -c 3 nice -n 19 du -sh "$d" 2>/dev/null
    ionice -c 3 nice -n 19 ls -la "$d" 2>/dev/null | head -15
  else
    echo "NO _data DIR"
  fi
  echo
  } >> "$OUT"
  i=$((i+1))
  if [ $((i % 8)) -eq 0 ]; then sleep 5; else sleep 1; fi
done < /tmp/p3_dangling_volumes.txt
echo "P3_VOL_DONE batches=$i"
