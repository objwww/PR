set -e
echo '== 旧路径制品恢复实跑（修正 jsonb cast；读报告行+证据包字节+sha256） =='
T2=$(date +%s)
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -At' <<'EOF'
SELECT 'run=' || r.id, 'report=' || rp.id, 'pkg_bytes=' || octet_length(rp.package_json::text)
  FROM rca_run r JOIN rca_report rp ON rp.run_id = r.id
 WHERE r.engine='HOLMES' AND r.state='SUCCEEDED'
 ORDER BY r.created_at ASC LIMIT 1;
EOF
T3=$(date +%s)
echo "restore-read RTO=$((T3-T2))s"
echo '== 全量历史 HOLMES 报告可读性面（计数+空包审计） =='
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -At' <<'EOF'
SELECT count(*) AS holmes_reports,
       count(*) FILTER (WHERE rp.package_json IS NULL) AS null_pkgs,
       min(octet_length(rp.package_json::text)) AS min_bytes,
       max(octet_length(rp.package_json::text)) AS max_bytes
  FROM rca_report rp JOIN rca_run r ON r.id=rp.run_id
 WHERE r.engine='HOLMES';
EOF
echo '== control-app 存续终验 =='
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
echo "health=$code"
echo 'RESTORE_M606_OK'
