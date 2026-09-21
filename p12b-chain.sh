#!/bin/sh
# p12b 链：等 B1 终态 → SUCCEEDED 才发 B2 → 全程写日志
RUN1=91aa342a-0332-4df4-ae7f-7ad9e05118ca
LOG=/tmp/p12b-chain.log
{
  echo "chain start $(date '+%F %T')"
  while true; do
    S=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
      "select state from eval_run where id='$RUN1'")
    case "$S" in SUCCEEDED|FAILED|CANCELLED) break ;; esac
    sleep 60
  done
  echo "B1 terminal=$S $(date '+%F %T')"
  if [ "$S" = "SUCCEEDED" ]; then
    sh /tmp/p12b-launch-b2.sh 2>&1 | tail -4
    echo "B2 launched $(date '+%F %T')"
  else
    echo "B1 NOT SUCCEEDED — B2 not launched"
  fi
  echo "chain done $(date '+%F %T')"
} >> "$LOG" 2>&1
