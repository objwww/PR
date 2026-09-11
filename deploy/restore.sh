#!/usr/bin/env bash
# ============================================================================
# EN-10 / O09 恢复脚本：从 backup.sh 产物恢复 pr_agent 库 + 依赖完整性核验。
#
# 用法（在 deploy/ compose 项目目录执行；**危险操作：--clean 会覆盖现库对象**）：
#   bash restore.sh backups/pr_agent-<ts>.dump           # 校验 + 二次确认后恢复
#   bash restore.sh backups/pr_agent-<ts>.dump --yes     # 免交互（自动化）
#
# 流程：sha256 指纹校验（有 .sha256 才做，缺失如实降级提示）→ pg_restore --list
# TOC 完整性 → pg_restore --clean --if-exists 恢复 → 计时输出 RTO → 关键面核验：
#   - flyway_schema_history 最高迁移版本（迁移历史完整）
#   - release_asset 行数 + asset_digest 全部 64 位小写 hex（内容寻址身份面形状完整）
#   - config_bundle / config_bundle_active 行数与当前指针（版本中心依赖面，如实打印）
#   - rca_run / incident / alert_incident 行数快照（供人工与故障前基线对拍——
#     脚本无法自证"恢复后 == 故障前"，对拍由值守按本输出完成，不伪验证）
#
# 诚实边界：canonical digest 重算（用 canonical JSON 反算 asset_digest 是否与
# 行值一致）需应用层 Digest 算法参与，脚本只做形状校验；O09 真值（RPO/RTO 实测、
# 真库对拍）以 195 恢复演练记录为准。
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

DB_NAME="${POSTGRES_DB:-pr_agent}"
DUMP_FILE="${1:-}"
ASSUME_YES="${2:-}"

[ -n "$DUMP_FILE" ] || { echo "用法：bash restore.sh <backup.dump> [--yes]" >&2; exit 2; }
[ -f "$DUMP_FILE" ] || { echo "[restore] FAIL：备份文件不存在：$DUMP_FILE" >&2; exit 2; }

# 1) 文件完整性：有 .sha256 就验（不验则降级提示，不冒充已校验）
SHA_FILE="$DUMP_FILE.sha256"
if [ -f "$SHA_FILE" ]; then
  EXPECTED="$(awk '{print $1}' "$SHA_FILE")"
  ACTUAL="$(sha256sum "$DUMP_FILE" | awk '{print $1}')"
  if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "[restore] FAIL：sha256 不匹配（expected=$EXPECTED actual=$ACTUAL），拒绝恢复" >&2
    exit 1
  fi
  echo "[restore] sha256 校验通过：$ACTUAL"
else
  echo "[restore] WARN：无 $SHA_FILE，跳过文件指纹校验（仅 TOC 校验兜底）" >&2
fi

# 2) 归档结构完整性
if ! docker compose exec -T postgres pg_restore --list < "$DUMP_FILE" > /dev/null 2>&1; then
  echo "[restore] FAIL：pg_restore --list 无法解析归档（TOC 损坏），拒绝恢复" >&2
  exit 1
fi
echo "[restore] TOC 完整性通过：$(docker compose exec -T postgres pg_restore --list < "$DUMP_FILE" | grep -c '^[0-9]') 个对象"

# 3) 二次确认（--yes 免交互）；覆盖现库是不可逆动作，必须显式放行
if [ "$ASSUME_YES" != "--yes" ]; then
  read -r -p "[restore] 将用 $DUMP_FILE --clean 覆盖库 $DB_NAME，确认请输入 RESTORE：" ack
  [ "$ack" = "RESTORE" ] || { echo "[restore] 已取消（未做任何变更）"; exit 0; }
fi

# 4) 恢复 + RTO 计时
START_TS="$(date +%s)"
docker compose exec -T postgres pg_restore -U postgres -d "$DB_NAME" \
  --clean --if-exists --no-privileges < "$DUMP_FILE"
END_TS="$(date +%s)"
RTO=$((END_TS - START_TS))
echo "[restore] 恢复完成，RTO=${RTO}s"

# 5) 依赖完整性核验（每项独立打印，缺表即 FAIL 行——诚实输出而非吞错）
q() { docker compose exec -T postgres psql -U postgres -d "$DB_NAME" -tA -c "$1"; }

MIG_MAX="$(q "select coalesce(max(version),'(空)') from flyway_schema_history")" \
  || { echo "[restore] FAIL：flyway_schema_history 不可读"; exit 1; }
echo "[restore] 迁移历史：最高版本 $MIG_MAX"

ASSET_TOTAL="$(q "select count(*) from release_asset")"
ASSET_BAD="$(q "select count(*) from release_asset where asset_digest !~ '^[0-9a-f]{64}$'")"
echo "[restore] release_asset：${ASSET_TOTAL} 行，digest 形状异常 ${ASSET_BAD} 行$([ "$ASSET_BAD" != "0" ] && echo '（FAIL：存在非 64hex 身份行）')"
[ "$ASSET_BAD" = "0" ] || exit 1

BUNDLE_ROWS="$(q "select count(*) from config_bundle")"
POINTER_ROWS="$(q "select count(*) from config_bundle_active")"
echo "[restore] config_bundle：${BUNDLE_ROWS} 行；当前指针行：${POINTER_ROWS} 行（0 = 无激活指针，如实呈现）"

for t in rca_run incident alert_incident; do
  # 表可能随版本演进改名/移除：查不到就如实标 N/A，不硬编码假装恒存在
  ROWS="$(q "select count(*) from $t" 2>/dev/null || echo "N/A（表不存在）")"
  echo "[restore] 对拍快照 $t：${ROWS} 行"
done

BACKUP_AGE_HOURS=$(( ($(date +%s) - $(stat -c %Y "$DUMP_FILE")) / 3600 ))
echo "[restore] 备份时点距现在 ${BACKUP_AGE_HOURS}h（RPO 实际损失参照；与故障时刻取更小者）"
echo "[restore] DONE：以上为恢复后真实快照，请值守与故障前基线对拍后宣告恢复成功"
