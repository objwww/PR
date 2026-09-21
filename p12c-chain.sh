#!/bin/sh
# p12c 链 v2：等 B1'（$1）终态 → SUCCEEDED 才发 B2
RUN1="$1"
LOG=/tmp/p12c-chain.log
{
  echo "chain start $(date '+%F %T') waiting=$RUN1"
  while true; do
    S=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
      "select state from eval_run where id='$RUN1'")
    case "$S" in SUCCEEDED|FAILED|CANCELLED) break ;; esac
    sleep 60
  done
  echo "B1p terminal=$S $(date '+%F %T')"
  if [ "$S" = "SUCCEEDED" ]; then
    sh /tmp/p12c-launch-b2.sh 2>&1 | tail -4
    echo "B2 launched $(date '+%F %T')"
  else
    echo "B1p NOT SUCCEEDED — B2 not launched"
  fi
  echo "chain done $(date '+%F %T')"
} >> "$LOG" 2>&1
