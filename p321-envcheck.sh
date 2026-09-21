#!/bin/sh
cd /opt/build/pr/deploy
echo "当前 .env 口令行前 30: $(grep '^AUTH_OPERATOR_PASSWORD_BCRYPT=' .env | head -c 50)..."
echo "userdemo 备份口令行前 30: $(grep '^AUTH_OPERATOR_PASSWORD_BCRYPT=' /tmp/env-backup-userdemo-20260917T015241 | head -c 50)..."
echo "p315 恢复源口令行前 30: $(grep '^AUTH_OPERATOR_PASSWORD_BCRYPT=' /tmp/env-backup-p315shot-20260916T234813 | head -c 50)..."
ls -la --time-style=+%m-%d_%H:%M /opt/build/pr/deploy/.env
