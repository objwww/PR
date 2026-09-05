#!/usr/bin/env bash
echo "== lexical first 15 =="
head -15 /tmp/all_tags.txt
echo "== lexical last 15 =="
tail -15 /tmp/all_tags.txt
echo "== count containing 'stable' =="
grep -c stable /tmp/all_tags.txt
echo "== stable samples =="
grep stable /tmp/all_tags.txt | head -8
echo "== count v-prefixed version tags =="
grep -cE 'main-v1\.[0-9]' /tmp/all_tags.txt
echo "== highest main-v tags (sort -V) =="
grep -E 'main-v1\.[0-9]+\.[0-9]+$' /tmp/all_tags.txt | sort -V | tail -5
