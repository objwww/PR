# O09 备份恢复演练 — 2026-09-11（§六③，195 真机）

## 终态：演练成立（RTO=46s，95/96 表精确对拍全等，1 表为备份后活库自然漂移）

## 演练路径（红线全程守住：pr_agent 只读，恢复目标=一次性 scratch 库）

1. **出档**：`bash backup.sh`（deploy/ 目录）→ `pg_dump -Fc` 流式回传 →
   `backups/pr_agent-20260912-002341.dump`（248,883,261 B，chmod 600）+ 同名 .sha256
   （`006a194ee70c5c823a05887e566bc5755b3633f15cb947bd01d18abcfcb323dd`）；
   脚本自带 0 字节断言 + `pg_restore --list` TOC 完整性断言均过。
2. **恢复**：`docker exec postgres createdb it_scratch_o09`（一次性 scratch）→
   `POSTGRES_DB=it_scratch_o09 bash restore.sh <dump> --yes`：
   - sha256 指纹校验通过（expected=actual 同值）；
   - TOC 完整性通过：1151 个对象；
   - `pg_restore --clean --if-exists --no-privileges` 恢复，**RTO=46s**；
   - 恢复后核验：release_asset 0 行 digest 形状异常 0 行、config_bundle 51 行 +
     指针 1 行、rca_run 276 行、incident 90 行、alert_incident N/A（表不存在，
     脚本如实标注）。
3. **对拍**（digest 双面）：
   - 文件面：.sha256 指纹 restore 侧重算同值（见上）；
   - 数据面：双库逐表精确 `count(*)` 对拍——**95 表 OK 全等**；1 表
     `canary_route_decision src=1787 dst=1784`：备份起点（00:23:41Z）后活库继续
     写入 3 行（canary 决策流），属点位快照 vs 活库的自然漂移，非恢复丢失
     （dst=备份时点真值）。
4. **收尾**：`dropdb it_scratch_o09` → `pg_database` 查 0 行实证已删。

## 三跑到达史（如实）

- 跑 1：195 deploy/ 无 backup.sh/restore.sh——**r7fix8.tar.gz 不含 deploy 脚本**
  （包仅 control-app/src+target，与 pom.xml 缺口同根，详见 IT-EN真值 SUMMARY）。
  执行者 scp 同步两脚本（md5 双侧对拍）后重跑。
- 跑 2：`set: pipefail: invalid option name`——**Windows CRLF**（本地工作树 CRLF 随
  scp 上 195，`\r` 进 set 选项名）。`tr -d '\r'` 剥离 + `bash -n` 双绿后通过
  （195 侧 md5：backup.sh 00b9d696…、restore.sh bfed6be8…→修 max 版本后 9bff00e5…）。
- 跑 3：上终态。

## 顺手纠正（脚本小缺陷，已修）

`restore.sh` 的「迁移历史：最高版本」原为 `max(version)`——version 是 varchar，
字典序 '9'>'88'，实测输出误导性的「最高版本 9」。已改 `max(version::bigint)`，
O09 复跑不再需要（该行为只读查询；修复留 195 待下次演练/真实故障时生效）。

## 诚实边界（承 restore.sh 头注）

canonical digest 重算需应用层参与，脚本只做形状校验；本演练的「恢复成功」以
行数对拍 + 指纹/TOC 完整性 + 恢复后关键面快照为准。

## 证据清单

- `o09-console.log`：终跑全程（backup/restore/对拍/收尾）
- `rowcount-exact.txt`：96 表精确 count 对拍明细（95 OK + 1 漂移）
- `rowcount-live-diff.txt`：pg_stat 统计面 diff（非精确值，被精确复核覆盖）
- `pr_agent-20260912-002341.dump.sha256`：出档指纹
- 195：/opt/r7-e2e/runs/o09-20260911/、/opt/build/pr/deploy/backups/（滚动保留窗口内）
