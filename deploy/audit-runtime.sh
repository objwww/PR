#!/bin/sh
# OR-01 运行时事实采集与对拍（总方案 §4）：只读、脱敏白名单、无秘密值/哈希不采
#
# 用法（在目标机部署目录旁执行；默认 compose 项目目录 = ./deploy）：
#   audit-runtime.sh --project-dir /opt/build/pr/deploy            # 采集 → runtime-manifest.json
#   audit-runtime.sh --project-dir ... --emit-expect               # 从构建树生成 → test-manifest.json
#   audit-runtime.sh --project-dir ... --expect test-manifest.json # 采集+对拍 → verdict（MATCH/DRIFT/UNKNOWN）
#
# 采集白名单（§4）：主机角色/镜像digest/build身份/Flyway版本/releaseDigest/模型路由/
#   能力开关/时区NTP/资源限制。秘密（KEY/TOKEN/PASSWORD/SECRET/URL 凭据段）一律不采；
#   prompt 只记 sha256 与长度；模型路由只记主机名。UNKNOWN 不当 MATCH（§4 纪律）。
set -u

PROJECT_DIR="deploy"
OUT="runtime-manifest.json"
EXPECT=""
EMIT=0
while [ $# -gt 0 ]; do
    case "$1" in
        --project-dir) PROJECT_DIR="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --expect) EXPECT="$2"; shift 2 ;;
        --emit-expect) EMIT=1; shift ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
cd "$PROJECT_DIR" || { echo "project dir not found: $PROJECT_DIR" >&2; exit 2; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ---------- 通用小工具 ----------
envwl() {  # 运行容器内白名单键（值原样，调用方只入白名单键；无 env dump）
    docker exec deploy-control-app-1 sh -c 'env' 2>/dev/null
}
sha256s() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
hostonly() { printf '%s' "$1" | sed -E 's#^[a-zA-Z]+://([^/@:]+).*#\1#'; }

# ---------- A. 主机面 ----------
{
    echo "collected_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host_name=$(hostname)"
    echo "host_kernel=$(uname -s -r 2>/dev/null | tr ' ' '-')"
    echo "timezone=$(timedatectl show -p Timezone --value 2>/dev/null || echo UNKNOWN)"
    echo "ntp_sync=$(timedatectl show -p NTPSynchronized --value 2>/dev/null || echo UNKNOWN)"
} > "$TMP/host.txt"

# ---------- B. 容器面（compose 服务：镜像/digest/资源限制/重启策略）----------
: > "$TMP/containers.tsv"
for c in $(docker compose ps -q 2>/dev/null); do
    docker inspect "$c" --format '{{.Name}}|{{.Config.Image}}|{{.Image}}|{{.State.Status}}|{{.HostConfig.RestartPolicy.Name}}|{{.HostConfig.Memory}}|{{.HostConfig.NanoCpus}}|{{.State.StartedAt}}' >> "$TMP/containers.tsv" 2>/dev/null
done

# ---------- C. 应用构建身份（镜像内 jar 指纹 + commit 标签如有）----------
docker exec deploy-control-app-1 sh -c 'md5sum /app/app.jar 2>/dev/null || md5sum /app/*.jar 2>/dev/null | head -1' > "$TMP/jar.txt" 2>/dev/null || echo "UNKNOWN" > "$TMP/jar.txt"
docker inspect deploy-control-app-1 --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' > "$TMP/commit.txt" 2>/dev/null || true

# ---------- D. 数据库迁移面（Flyway 版本/描述/success；只读）----------
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -A -t -F'|' -c \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank" \
    > "$TMP/flyway.tsv" 2>/dev/null || echo "QUERY_FAILED" > "$TMP/flyway.tsv"

# ---------- E. 配置面（白名单键 + release 资产/技能绑定计数）----------
ENVW=$(envwl)
for k in AGENT_MODEL APP_ALERT_R7_PRIMARY_MAX_DELEGATION_BATCHES \
         APP_ALERT_AM4_BUDGET_TOOL_CALLS APP_ALERT_AM4_BUDGET_STEP \
         APP_ALERT_AM4_PROMETHEUS_SERVICE_ALLOWLIST; do
    v=$(printf '%s\n' "$ENVW" | grep -m1 "^$k=" | cut -d= -f2-)
    [ -n "$v" ] && echo "$k=$v" >> "$TMP/envwl.txt" || echo "$k=ABSENT" >> "$TMP/envwl.txt"
done
PROMPT=$(printf '%s\n' "$ENVW" | grep -m1 '^APP_ALERT_R7_PRIMARY_PROMPT=' | cut -d= -f2-)
if [ -n "$PROMPT" ]; then
    echo "prompt_sha256=$(sha256s "$PROMPT")" >> "$TMP/envwl.txt"
    echo "prompt_len=${#PROMPT}" >> "$TMP/envwl.txt"
else
    echo "prompt_sha256=ABSENT" >> "$TMP/envwl.txt"
fi
BASEURL=$(printf '%s\n' "$ENVW" | grep -m1 '^OPENAI_COMPAT_BASE_URL=' | cut -d= -f2-)
echo "model_route_host=$(hostonly "$BASEURL")" >> "$TMP/envwl.txt"

docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -A -t -F'|' -c \
    "SELECT asset_kind, status, count(*) FROM release_asset GROUP BY 1,2 ORDER BY 1,2" \
    > "$TMP/release.tsv" 2>/dev/null || echo "QUERY_FAILED" > "$TMP/release.tsv"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -A -t -c \
    "SELECT count(*) FROM rca_run_skill_binding" \
    > "$TMP/skillbind.txt" 2>/dev/null || echo "QUERY_FAILED" > "$TMP/skillbind.txt"

# ---------- F. 组装 / 对拍（python3）----------
BUILD_MIG_DIR="control-app/src/main/resources/db/migration"   # 相对仓库根（项目目录之上一级）
python3 - "$TMP" "$OUT" "$EXPECT" "$EMIT" "$BUILD_MIG_DIR" <<'PYEOF'
import hashlib, json, os, re, subprocess, sys
tmp, out, expect, emit, migdir = sys.argv[1:6]
emit = emit == "1"

def rd(name, default=""):
    p = os.path.join(tmp, name)
    if not os.path.exists(p):
        return default
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read().strip()

def kv(name):
    d = {}
    for line in rd(name).splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            d[k] = v
    return d

manifest = {"schema": "or01-runtime-manifest/1"}
manifest["meta"] = kv("host.txt")

containers = []
for line in rd("containers.tsv").splitlines():
    f = line.split("|")
    if len(f) >= 7:
        containers.append({"name": f[0].lstrip("/"), "image_ref": f[1],
                           "image_id": f[2][:19] if f[2] else "UNKNOWN",
                           "state": f[3], "restart": f[4],
                           "mem_limit": int(f[5]) if f[5].isdigit() else None,
                           "nanocpus": int(f[6]) if f[6].isdigit() else None,
                           "started_at": f[7] if len(f) > 7 else ""})
manifest["containers"] = containers

jar = rd("jar.txt") or "UNKNOWN"
jar_md5 = jar.split()[0] if jar and jar != "UNKNOWN" else "UNKNOWN"
commit = rd("commit.txt") or ""
manifest["app_build"] = {
    "jar_md5": jar_md5,
    "git_commit": commit if commit else "UNKNOWN",
    "note": "镜像未嵌 commit 标签时记 UNKNOWN——发布资格须绑定构建来源（OR-11）",
}

flyway = []
qfail = False
for line in rd("flyway.tsv").splitlines():
    if line == "QUERY_FAILED":
        qfail = True
        break
    f = line.split("|")
    if len(f) == 3:
        flyway.append({"version": f[0], "description": f[1], "success": f[2]})
manifest["flyway"] = {"rows": flyway, "query_ok": not qfail}

envwl = kv("envwl.txt")
manifest["config"] = envwl

rel = []
for line in rd("release.tsv").splitlines():
    f = line.split("|")
    if len(f) == 3:
        rel.append({"kind": f[0], "status": f[1], "count": f[2]})
manifest["release_assets"] = rel
sb = rd("skillbind.txt") or "UNKNOWN"
manifest["skill_binding_rows"] = sb if sb.isdigit() else "UNKNOWN"

# ---- 期望面（构建树）：git 身份 + 迁移文件清单 ----
if emit:
    expect_m = {"schema": "or01-test-manifest/1"}
    try:
        repo = ".."
        head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True,
                              text=True, cwd=repo).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain"], capture_output=True,
                               text=True, cwd=repo).stdout
        expect_m["build"] = {"git_commit": head,
                             "worktree_dirty": bool(dirty.strip()),
                             "note": "工作树非空=运行面可能含未提交码（快照构建）"}
    except Exception:
        expect_m["build"] = {"git_commit": "NO_GIT", "worktree_dirty": None}
    migs = []
    mdir = os.path.join("..", migdir)
    if os.path.isdir(mdir):
        for fn in sorted(os.listdir(mdir)):
            if fn.endswith(".sql"):
                migs.append(fn[:-4])
    expect_m["migrations_expected"] = migs
    with open(out, "w", encoding="utf-8") as f:
        json.dump(expect_m, f, ensure_ascii=False, indent=2)
    print(json.dumps(expect_m, ensure_ascii=False, indent=2))
    sys.exit(0)

with open(out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)

# ---- 对拍 ----
if not expect:
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    sys.exit(0)

with open(expect, encoding="utf-8") as f:
    exp = json.load(f)

verdicts = []
def judge(key, status, detail):
    verdicts.append({"key": key, "verdict": status, "detail": detail})

# 1. 迁移集：运行 applied vs 构建树文件集（库 version=纯数字；文件名 V<n>__desc → <n>）
applied = {r["version"] for r in manifest["flyway"]["rows"]}
expmig = {m[1:].split("__")[0] for m in exp.get("migrations_expected", [])}
missing_in_db = sorted(expmig - applied)
extra_in_db = sorted(applied - expmig)
if not manifest["flyway"]["query_ok"]:
    judge("flyway", "UNKNOWN", "采集查询失败")
elif not missing_in_db and not extra_in_db:
    judge("flyway", "MATCH", f"{len(applied)} 版本一致")
else:
    judge("flyway", "DRIFT", f"库缺={missing_in_db} 库多={extra_in_db}")

# 2. 构建身份：运行镜像 commit vs 构建树 commit
rc = manifest["app_build"]["git_commit"]
ec = exp.get("build", {}).get("git_commit", "NO_GIT")
if rc == "UNKNOWN":
    judge("app_git_commit", "UNKNOWN", "镜像未嵌 commit 身份——无法对拍（OR-11 补构建来源绑定）")
elif rc == ec and not exp.get("build", {}).get("worktree_dirty"):
    judge("app_git_commit", "MATCH", rc[:12])
else:
    judge("app_git_commit", "DRIFT",
          f"运行={rc[:12]} 构建={ec[:12]} 脏树={exp.get('build', {}).get('worktree_dirty')}")

# 3. 白名单配置：缺省键=部署层未钉（走应用内默认）记 UNKNOWN；值对拍留 RR02 漂移用例
absent = [k for k, v in manifest["config"].items() if v == "ABSENT"]
judge("config_whitelist", "UNKNOWN" if absent else "MATCH",
      f"部署层未钉（走应用默认）={absent}" if absent
      else f"{len(manifest['config'])} 键全部显式钉定")

# 4. 容器/资源面在场性
no_limit = [c["name"] for c in manifest["containers"] if not c["mem_limit"]]
judge("container_mem_limits", "MATCH" if not no_limit else "DRIFT",
      f"未设内存上限={no_limit}" if no_limit else f"{len(manifest['containers'])} 容器全有上限")

# 5. Skill/RAG 绑定计数在场
judge("skill_binding_rows", "MATCH" if manifest["skill_binding_rows"] != "UNKNOWN" else "UNKNOWN",
      f"rows={manifest['skill_binding_rows']}")

overall = ("DRIFT" if any(v["verdict"] == "DRIFT" for v in verdicts)
           else "UNKNOWN" if any(v["verdict"] == "UNKNOWN" for v in verdicts) else "MATCH")
report = {"schema": "or01-verdict/1", "overall": overall,
          "manifest_out": out, "expect_file": expect, "verdicts": verdicts}
print(json.dumps(report, ensure_ascii=False, indent=2))
sys.exit(0 if overall == "MATCH" else 1)
PYEOF
