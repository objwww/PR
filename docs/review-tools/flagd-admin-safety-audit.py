"""Local characterization of actual sidecar file operations. No server or network."""
import importlib.util
import json
import pathlib
import sys
import tempfile
import threading

sys.dont_write_bytecode = True
root = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("flagd_audit_target", root / "deploy/alert/flagd-admin/server.py")
target = importlib.util.module_from_spec(spec)
spec.loader.exec_module(target)
with tempfile.TemporaryDirectory(prefix="flagd-local-audit-") as directory:
    file = pathlib.Path(directory) / "flags.json"
    target.FLAGD_FILE = str(file)
    initial = {"flags": {name: {"defaultVariant": "off", "variants": {"off": False, "on": True}} for name in ("a", "b")}}
    file.write_text(json.dumps(initial), encoding="utf-8")
    token0 = target.read_flag("a")[1]
    # Same content rewritten cannot advance the token; A -> B -> A also restores the old token.
    file.write_text(json.dumps(initial), encoding="utf-8")
    assert target.read_flag("a")[1] == token0
    changed = json.loads(json.dumps(initial))
    changed["flags"]["a"]["defaultVariant"] = "on"
    file.write_text(json.dumps(changed), encoding="utf-8")
    assert target.read_flag("a")[1] != token0
    file.write_text(json.dumps(initial), encoding="utf-8")
    assert target.read_flag("a")[1] == token0
    print("SAFE-03 content hash is unchanged on identical rewrite and repeats after ABA; not a monotonic generation")

    barrier = threading.Barrier(2, timeout=5)
    original_load = target.json.load
    original_fchmod = getattr(target.os, "fchmod", None)
    def synchronized_load(handle, *args, **kwargs):
        doc = original_load(handle, *args, **kwargs)
        if threading.current_thread().name.startswith("audit-writer-"):
            barrier.wait()
        return doc
    target.json.load = synchronized_load
    if original_fchmod is None:
        target.os.fchmod = lambda fd, mode: None  # Windows test-only compatibility; content logic unchanged.
    failures = []
    def write_one(flag):
        try:
            target.set_default_variant(flag, "on")
        except BaseException as exc:
            failures.append(exc)
    try:
        workers = [threading.Thread(target=write_one, args=(flag,), name="audit-writer-" + flag) for flag in ("a", "b")]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join(timeout=10)
        assert not any(worker.is_alive() for worker in workers)
        assert not failures, failures
    finally:
        target.json.load = original_load
        if original_fchmod is None:
            del target.os.fchmod
    actual = json.loads(file.read_text(encoding="utf-8"))
    applied = [flag for flag in ("a", "b") if actual["flags"][flag]["defaultVariant"] == "on"]
    assert len(applied) == 1, actual
    print("SAFE-03 two concurrent successful writes to different flags preserve only one update:", applied)
print("Defect characterization complete; temporary files removed; zero real I/O to deployment.")
