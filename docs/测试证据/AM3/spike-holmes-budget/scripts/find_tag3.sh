#!/usr/bin/env bash
T=$(curl -s 'https://ghcr.io/token?scope=repository:berriai/litellm:pull' | sed 's/.*"token":"\([^"]*\)".*/\1/')
URL='https://ghcr.io/v2/berriai/litellm/tags/list'
> /tmp/all_tags.txt
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  RESP=$(curl -s -D /tmp/hdrs.txt -H "Authorization: Bearer $T" "$URL")
  echo "$RESP" | grep -o '"[^"]*"' | tr -d '"' >> /tmp/all_tags.txt
  LINK=$(grep -i '^link:' /tmp/hdrs.txt | sed 's/^[Ll]ink: *//' | tr -d '\r')
  if [ -z "$LINK" ]; then break; fi
  NEXT=$(echo "$LINK" | sed 's/[<>]//g' | cut -d';' -f1)
  case "$NEXT" in
    http*) URL="$NEXT" ;;
    *) URL="https://ghcr.io$NEXT" ;;
  esac
done
sort -u /tmp/all_tags.txt -o /tmp/all_tags.txt
echo "total_tags=$(wc -l < /tmp/all_tags.txt)"
echo "== v1.89 candidates =="
grep -E '1\.89' /tmp/all_tags.txt | head -10
echo "== recent stable tags =="
grep -E -- '-stable$' /tmp/all_tags.txt | grep -E 'v1\.(89|9[0-9])' | tail -8
