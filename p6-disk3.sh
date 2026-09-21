#!/bin/sh
echo '---biggest container logs---'
du -sh /var/lib/docker/containers/*/*-json.log 2>/dev/null | sort -rh | head -6
echo '---truncate >100M---'
for f in $(du -m /var/lib/docker/containers/*/*-json.log 2>/dev/null | awk '$1 > 100 {print $2}'); do
  echo "truncate $f ($(du -m "$f" | cut -f1)M)"
  truncate -s 0 "$f"
done
echo '---after---'
df -h / | tail -1
