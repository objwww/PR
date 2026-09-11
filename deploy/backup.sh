#!/usr/bin/env bash
# ============================================================================
# EN-10 / O09 备份脚本：pg_dump -Fc 全量备份 + sha256 指纹 + 滚动保留。
#
# 用法（在 deploy/ compose 项目目录执行；195 上 cron 驱动）：
#   bash backup.sh                 # 单次备份
#   BACKUP_KEEP=14 bash backup.sh  # 调整保留份数（默认 7）
#
# 产出：backups/pr_agent-<ts>.dump（chmod 600）+ 同名 .sha256 指纹文件。
#   restore.sh 恢复前会用 .sha256 验文件完整性（指纹缺失时降级为仅 TOC 校验，
#   并如实打印降级提示——不伪造校验通过）。
#
# RPO 口径（诚实边界）：RPO = 相邻两次成功备份的最大间隔，由 cron 频率决定
#   （建议 crontab：`17 */6 * * * cd /opt/build/pr/deploy && bash backup.sh`，
#   即 RPO ≤ 6h）。本脚本每行输出带时间戳，实际 RPO 以 195 生产实测为准。
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

OUT_DIR="backups"
KEEP="${BACKUP_KEEP:-7}"
DB_NAME="${POSTGRES_DB:-pr_agent}"

mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
DUMP_FILE="$OUT_DIR/${DB_NAME}-${TS}.dump"

# pg_dump custom format：经 docker compose exec 流式回传宿主机（pg-data 卷不直接触碰）
docker compose exec -T postgres pg_dump -Fc -U postgres -d "$DB_NAME" > "$DUMP_FILE"

# 备份非空断言：0 字节 dump 说明容器/管道异常，比静默留空壳更早暴露
if [ ! -s "$DUMP_FILE" ]; then
  rm -f "$DUMP_FILE"
  echo "[backup] FAIL：pg_dump 产出 0 字节（容器存活/管道检查），已删除空壳" >&2
  exit 1
fi

# TOC 可读 = 归档结构完整（pg_restore --list 成功才承认这次备份有效）
if ! docker compose exec -T postgres pg_restore --list < "$DUMP_FILE" > /dev/null 2>&1; then
  rm -f "$DUMP_FILE"
  echo "[backup] FAIL：pg_restore --list 无法解析归档（TOC 损坏），已删除坏档" >&2
  exit 1
fi

chmod 600 "$DUMP_FILE"
SHA256="$(sha256sum "$DUMP_FILE" | awk '{print $1}')"
echo "$SHA256  $(basename "$DUMP_FILE")" > "$DUMP_FILE.sha256"

# 滚动保留：超出 KEEP 的最老档（连同指纹）删除
ls -1t "$OUT_DIR"/${DB_NAME}-*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r old; do
  rm -f "$old" "$old.sha256"
done

OLDEST="$(ls -1 "$OUT_DIR"/${DB_NAME}-*.dump 2>/dev/null | sort | head -n 1 || true)"
echo "[backup] OK：$DUMP_FILE（$(du -h "$DUMP_FILE" | cut -f1)）sha256=$SHA256"
if [ -n "$OLDEST" ]; then
  echo "[backup] 保留窗口：$(basename "$OLDEST") 至今（RPO 参照，实际以 cron 频率与成功率为准）"
fi
