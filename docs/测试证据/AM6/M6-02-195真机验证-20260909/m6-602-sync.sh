set -e
TS=20260908T1900Z
echo '== 备份双树（先备份后变更） =='
tar -C /opt -czf /opt/backups/pre-m602-9d0e7d5-deploy-tree.tar.gz build/pr
tar -C /opt -czf /opt/backups/pre-m602-9d0e7d5-it-tree.tar.gz projects/pr_agent_it
ls -la /opt/backups/pre-m602-9d0e7d5-*.tar.gz
echo '== 解包双树 =='
tar -C /opt/build/pr -xzf /tmp/m6-602-tree.tgz
tar -C /opt/projects/pr_agent_it -xzf /tmp/m6-602-tree.tgz
echo '== 解包后条目级抽查（BA-34⑧/BA-51：行尾字节恒等面） =='
cd /opt/projects/pr_agent_it
sha256sum control-app/src/main/resources/db/migration/V32__am6_engine_comparison.sql control-app/src/main/java/com/objwww/pr/control/release/application/EngineComparisonRecorder.java deploy/policy/m6-engine-observation.sql
file control-app/src/main/resources/db/migration/V32__am6_engine_comparison.sql
echo '== 迁移面确认（V32 就位、V31 历史在案） =='
ls control-app/src/main/resources/db/migration/ | tail -4
