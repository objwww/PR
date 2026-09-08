# M6-06 195 真机验证证据包（Holmes 退场预演；2026-09-09）

- **验证对象**：M6-06 退场预演四件套——六面依赖扫描（脚本+191 行全日志）、drain barrier v2（真 PG 重放 B1~B4 全零 + 豁免台账 5 组）、恢复演练（kill-switch 双闸→holmesgpt 停机→存活 200→131 份历史 HOLMES 报告全可读 RTO=0s→制品封存）、退场姿态快照（双闸 false/镜像 ID/compose 归档/密钥键名）。
- **决策出口**：`../../../告警AM6-Holmes退场决策记录.md`（本包为其唯一取数源；总裁定=批准进入 M6-07）。
- **取证方式（诚实留痕）**：首轮六面扫描/drain/演练/封存执行于 2026-09-09 05:55~06:43（产物已封 `/opt/backups/pre-m607-holmes-removal/`）；本包 `m6-606-capture.log` 为**同日只读复验+取证重放**（drain SQL 只读重放、restore 只读复读重计时、姿态快照），零状态变更。执行会话日志未跨会话存续，故以可重复的只读重放为准——所有数字均可由本包脚本在 195 上一键复现。

## 195 终态（取证时点）

- holmesgpt-am1 **Exited (0)**；control-app health **200**（holmesgpt 停机下持续存续）；control-app 镜像 `sha256:baaaa58ff68f…`（与 M6-05 证据包同锚）。
- `.env`：`APP_ALERT_SHADOW_HOLMES_ENABLED=false` + `APP_ALERT_FALLBACK_ENABLED=false`（备份 `dotenv-pre-removal.bak` 600）；HOLMES 三键仍在位待 M6-07 回收（只录键名，值零回显）。
- 封存：`/opt/backups/pre-m607-holmes-removal/{alert,deploy}-compose-pre-removal.yml + dotenv-pre-removal.bak + holmesgpt-image-digest.txt`（镜像 `sha256:d0a0518d…`）；git tag `pre-holmes-removal` 本地打标（不 push）。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| m6-dependency-scan.sh | 六面扫描脚本（195 宿主执行；repo 权威件在 `scripts/`） |
| m6-606-scan-full.log | 扫描全日志 191 行（`M6_DEPENDENCY_SCAN_OK`；六面裁定齐） |
| m6-606-drain.sql / m6-606-run-drain.sh | drain barrier v2（CANCELLED 计终态修正版）+ psql stdin 执行器 |
| m6-606-discover.sh / m6-606-zombie.sql.sh / m6-606-zombie2.sql.sh | barrier v1 假阳性取证链（CANCELLED 终态甄别过程留痕，只增不删） |
| m6-606-drill.sh | 恢复演练驱动（.env 备份→双闸 false→compose up→health→docker stop holmesgpt→存活检查→镜像 digest 封存+compose/.env 归档） |
| m6-606-restore.sh | 旧路径制品恢复实跑（最旧报告真读+sha256+RTO 计时+131 报告全量可读性审计） |
| m6-606-capture.sh | 只读取证脚本（扫描日志回读+drain 重放+restore 重放+姿态快照） |
| m6-606-capture.log | **本包主证据日志**：§A 扫描回读（191 行头尾）、§B drain B1=0/B2=0/B3=0/B4=0+B5 五组、§C RTO=0s+131|0|382|3470+health 200、§D 姿态（Exited(0)/200/键名/封存清单/镜像 digest）+ 附录双闸布尔值 |

密钥面：全件零密钥（.env 值、bearer、PG 口令零回显；入 git 前密钥字样扫描零命中）。
