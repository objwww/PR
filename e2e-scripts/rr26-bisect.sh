#!/bin/sh
F=/opt/build/rr26-run.sh
n=0
while IFS= read -r line; do
  n=$((n+1))
  c=$(printf '%s' "$line" | tr -cd "'" | wc -c)
  if [ $((c % 2)) -eq 1 ]; then echo "line $n odd-squote ($c): $line"; fi
done < "$F"
echo SQUOTE-SCAN-DONE
