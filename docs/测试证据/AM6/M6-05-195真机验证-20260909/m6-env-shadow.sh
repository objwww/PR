set -e
cd /opt/build/pr/deploy
echo '== 备份 .env（先备份后变更） =='
cp -a .env /opt/backups/pre-m605-dotenv-$(date +%Y%m%d%H%M%S).bak
ls -la /opt/backups/pre-m605-dotenv-*.bak | tail -1
echo '== 幂等开启影子抽样闸（只写开关键名；值非密钥可核对出现次数） =='
if grep -q '^APP_ALERT_SHADOW_HOLMES_ENABLED=' .env; then
  sed -i 's/^APP_ALERT_SHADOW_HOLMES_ENABLED=.*/APP_ALERT_SHADOW_HOLMES_ENABLED=true/' .env
else
  printf '\n# AM6 M6-05 Holmes 只读对照期：抽样闸开启（由 m6-env-shadow.sh 落）\nAPP_ALERT_SHADOW_HOLMES_ENABLED=true\n' >> .env
fi
echo -n 'enabled 键出现次数(期望1): '
grep -c '^APP_ALERT_SHADOW_HOLMES_ENABLED=true' .env
echo 'ENV_SHADOW_OK'
