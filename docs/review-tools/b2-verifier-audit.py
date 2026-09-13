"""Offline audit: run archived verifier with fake DB output; no network or DB actions."""
import builtins
import contextlib
import hashlib
import io
import json
from pathlib import Path
import runpy
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'docs/测试证据/R7/runs/b2-cl06-20260913/scripts/b2-cl06-verify.py'
EMPTY = dict(hypotheses=[], ruled_out=[], counter_evidence_refs=[], open_gaps=[])

def audit(name, memories, corrupt=False):
    records = []
    for seq, memory in enumerate(memories):
        prompt = json.dumps({'working_memory': memory}, ensure_ascii=False)
        digest = hashlib.sha256(prompt.encode()).hexdigest()
        records.append('\x1f'.join([str(seq), str(seq), 'FULL', prompt,
                                     '0' * 64 if corrupt else digest]))
    payload = '\x1e'.join(records).encode()
    class Process:
        returncode = 0
        def communicate(self):
            return payload, b''
    original = builtins.open
    def fake_open(path, mode='r', *args, **kwargs):
        if str(path) == '/tmp/b2cl06/run-id.txt':
            return io.StringIO('run=00000000-0000-4000-8000-000000000001')
        if str(path).startswith('/tmp/b2cl06/prompt-'):
            return io.StringIO()
        return original(path, mode, *args, **kwargs)
    output = io.StringIO()
    exit_code = 0
    with patch('builtins.open', fake_open), patch('subprocess.Popen', return_value=Process()):
        with contextlib.redirect_stdout(output):
            try:
                runpy.run_path(str(TARGET), run_name='__main__')
            except SystemExit as error:
                exit_code = error.code
    verdict = output.getvalue().splitlines()[-1]
    return {'case': name, 'reported': verdict, 'exit_code': exit_code}

results = [
    audit('empty_parent_vacuous_pass', [EMPTY, EMPTY]),
    audit('digest_mismatch_returns_success_exit', [EMPTY, EMPTY], True),
    audit('missing_counter_evidence_returns_success_exit',
          [dict(EMPTY, counter_evidence_refs=['ref-A']), EMPTY]),
    audit('parent_prefix_reordered_not_rejected',
          [dict(EMPTY, counter_evidence_refs=['ref-A', 'ref-B']),
           dict(EMPTY, counter_evidence_refs=['ref-B', 'ref-A'])]),
]
print(json.dumps(results, ensure_ascii=False, indent=2))
