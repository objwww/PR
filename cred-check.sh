#!/bin/sh
# 检查 .env 与备份的 AUTH_OPERATOR 两行差异（不回显值）
cd /opt/build/pr/deploy
BK=/tmp/env-backup-e2e-cred-20260916T122557
echo "backup_lines:"; grep -c '^AUTH_OPERATOR' "$BK"
echo "current_lines:"; grep -c '^AUTH_OPERATOR' .env
echo "username_match:"; [ "$(grep '^AUTH_OPERATOR_USERNAME=' "$BK")" = "$(grep '^AUTH_OPERATOR_USERNAME=' .env)" ] && echo YES || echo NO
echo "bcrypt_len_backup=$(grep '^AUTH_OPERATOR_PASSWORD_BCRYPT=' "$BK" | head -1 | wc -c) bcrypt_len_current=$(grep '^AUTH_OPERATOR_PASSWORD_BCRYPT=' .env | head -1 | wc -c)"
echo "bcrypt_prefix_current=$(grep '^AUTH_OPERATOR_PASSWORD_BCRYPT=' .env | head -1 | cut -c1-12)"
exit 0
