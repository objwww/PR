#!/bin/sh
# RV 批 195 全量服务器测试：surefire 全量单测 + failsafe 真 PG IT（含新 T20/T21）
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
echo '=== mvn verify (control-app: 全量单测 + Testcontainers 真 PG IT) ==='
mvn -B -ntp -pl control-app verify 2>&1 | tee /tmp/rv-verify-full.log | grep -E 'Tests run:.*Failures|BUILD|ERROR\]' | tail -30
echo '=== 汇总面 ==='
echo '--- surefire 单测总计（最后一段） ---'
grep -E 'Tests run: [0-9]+, Failures' /tmp/rv-verify-full.log | tail -3
echo '--- failsafe IT 总计 ---'
grep -B2 -A2 'failsafe' /tmp/rv-verify-full.log | grep -E 'Tests run|Failsafe' | tail -6
echo '--- 新 IT T20/T21 逐条 ---'
grep -E 'PostgresDelegationReceiptConcurrencyIT' /tmp/rv-verify-full.log | tail -8
echo '--- 真 PG IT 套件清单（本窗实跑） ---'
grep -oE '(Postgres|Rca|Command)[A-Za-z]*IT\.' /tmp/rv-verify-full.log | sort -u | head -30
echo '--- BUILD 行 ---'
grep -E '^\[INFO\] BUILD' /tmp/rv-verify-full.log
echo TEST-DONE
