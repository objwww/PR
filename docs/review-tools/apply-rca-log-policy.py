"""Apply one reviewed prompt to the existing Compose deployment, preserving other settings."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid


def run(*args):
    return subprocess.check_output(args, universal_newlines=True)


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def main():
    prompt_path, expected = sys.argv[1:]
    prompt = Path(prompt_path).read_text(encoding="utf-8").strip()
    assert "primary-log-investigation-v" in prompt
    os.chdir("/opt/build/pr/deploy")
    key = "APP_ALERT_R7_PRIMARY_PROMPT"
    live = dict(item.split("=", 1) for item in json.loads(run(
        "docker", "inspect", "deploy-control-app-1", "--format", "{{json .Config.Env}}")))
    assert digest(live[key]) == expected, "Live prompt changed since preflight"
    config = json.loads(run("docker", "compose", "config", "--format", "json"))
    configured = config["services"]["control-app"]["environment"]
    # Compose renders literal dollars as $$, including bcrypt separators.
    drift = [k for k, v in configured.items() if str(v).replace("$$", "$") != live.get(k)]
    assert not drift, "Unreviewed environment drift: " + ",".join(drift)
    env = Path(".env")
    before = env.read_bytes()
    lines = before.decode("utf-8").splitlines(keepends=True)
    matches = [i for i, line in enumerate(lines) if line.startswith(key + "=")]
    assert len(matches) == 1, "Expected exactly one existing prompt setting"
    # JSON string quoting is also Compose's double-quoted dotenv syntax.
    lines[matches[0]] = key + "=" + json.dumps(prompt, ensure_ascii=False) + "\n"
    after = "".join(lines).encode("utf-8")
    backup = Path(".env.before-log-policy-" + str(uuid.uuid4()))
    shutil.copy2(env, backup)
    os.chmod(backup, 0o600)
    try:
        assert env.read_bytes() == before, "Environment changed during preflight"
        env.write_bytes(after)
        config = json.loads(run("docker", "compose", "config", "--format", "json"))
        assert config["services"]["control-app"]["environment"][key] == prompt
        subprocess.run(["docker", "compose", "up", "-d", "--no-deps", "control-app"], check=True)
        for _ in range(45):
            health = subprocess.run(["curl", "-fsS", "--max-time", "2",
                                     "http://127.0.0.1:8090/actuator/health"],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if health.returncode == 0:
                break
            time.sleep(2)
        else:
            raise RuntimeError("Control app did not become healthy")
        active = dict(item.split("=", 1) for item in json.loads(run(
            "docker", "inspect", "deploy-control-app-1", "--format", "{{json .Config.Env}}")))
        assert active[key] == prompt
        assert all(active.get(k) == v for k, v in live.items() if k != key), "Unexpected runtime env change"
    except BaseException:
        if env.read_bytes() == after:
            env.write_bytes(before)
            subprocess.run(["docker", "compose", "up", "-d", "--no-deps", "control-app"], check=True)
        else:
            print("Concurrent env edit detected; rollback backup: " + str(backup), file=sys.stderr)
        raise
    print(json.dumps({"old_prompt_sha256": expected, "new_prompt_sha256": digest(prompt),
                      "backup": str(backup.resolve()), "healthy": True}))


if __name__ == "__main__":
    main()
