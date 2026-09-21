"""Offline contract, pairing, scoring, isolation and HTTP tests. No paid calls."""
import copy
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib import error, request
from http.server import ThreadingHTTPServer

import lab


def dataset():
    return json.loads((lab.HERE / "example-dataset.json").read_text(encoding="utf-8"))


def cfg():
    return {"jev_url": "https://api.typesafe.ai/v1/systemone", "jev_model": "jev-1.13.0", "jev_key": "secret-jev",
            "llm_url": "http://127.0.0.1:9999/v1/chat/completions", "llm_model": "test-model", "llm_key": "secret-llm",
            "prices": {"jev_input": .042, "jev_output": 0, "llm_input": 1, "llm_output": 2, "llm_cached_input": .1}}


class DatasetTests(unittest.TestCase):
    def test_sample_and_gold_refs_are_valid(self):
        lab.validate_dataset(dataset())
        d = dataset()
        d["cases"][0]["gold"]["evidence_ids"].append("missing")
        with self.assertRaisesRegex(ValueError, "引用无效"):
            lab.validate_dataset(d)

    def test_duplicate_case_or_evidence_rejected(self):
        for modify in (lambda d: d["cases"].append(copy.deepcopy(d["cases"][0])),
                       lambda d: d["cases"][0]["evidence"].append(copy.deepcopy(d["cases"][0]["evidence"][0]))):
            d = dataset()
            modify(d)
            with self.assertRaises(ValueError):
                lab.validate_dataset(d)

    def test_bad_types_and_missing_cluster_rejected(self):
        for key, value in (("cluster_id", ""), ("gold", None), ("root_cause_catalog", {}), ("evidence", [None])):
            d = dataset()
            d["cases"][0][key] = value
            with self.assertRaises(ValueError):
                lab.validate_dataset(d)

    def test_rounds_bounds_fraction_bool_unknown_option(self):
        for raw in ({"rounds": 31}, {"rounds": 1.5}, {"rounds": True}, {"threshold": float("nan")}, {"extra": 1}):
            with self.assertRaises(ValueError):
                lab.options(raw)
        self.assertEqual(lab.options({})["rounds"], 30)

    def test_required_preserved_and_original_order(self):
        c = dataset()["cases"][0]
        scores = {e["id"]: 0 for e in c["evidence"]}
        chosen = lab.select_evidence(c, lab.options({}), scores)
        self.assertEqual([e["id"] for e in chosen], [c["evidence"][0]["id"]])
        scores[c["evidence"][-1]["id"]] = .9
        scores[c["evidence"][3]["id"]] = .7
        chosen = lab.select_evidence(c, lab.options({}), scores)
        self.assertEqual([e["id"] for e in chosen], [c["evidence"][i]["id"] for i in (0, 3, len(c["evidence"])-1)])

    def test_required_budget_failure_before_api(self):
        d = dataset()
        for e in d["cases"][0]["evidence"]:
            e["required"] = True
        with tempfile.TemporaryDirectory() as directory:
            runner = lab.Lab(directory, cfg())
            with self.assertRaisesRegex(ValueError, "必保留证据"):
                runner.start({"dataset": d, "mode": "live", "options": {"max_items": 1}})
            self.assertEqual(runner.listing(), [])

    def test_public_projection_never_sends_labels_or_metadata(self):
        c = dataset()["cases"][0]
        c["private_secret"] = "SHOULD_NOT_SEND"
        c["evidence"][0]["private"] = "SHOULD_NOT_SEND"
        value = lab.encoded(lab.public_case(c)).decode()
        for key in ('"gold"', '"cluster_id"', '"case_id"', "SHOULD_NOT_SEND"):
            self.assertNotIn(key, value)


class MetricTests(unittest.TestCase):
    def test_tp_fp_fn_and_empty_convention(self):
        m = lab.score_sets(["a", "b"], ["b", "c", "c"])
        self.assertEqual((m["tp"], m["fp"], m["fn"], m["f1"], m["recall"]), (1, 1, 1, .5, .5))
        self.assertEqual(lab.score_sets([], [])["f1"], 1)
        self.assertEqual(lab.score_sets(["a"], [])["recall"], 0)
        self.assertEqual(lab.score_sets([], ["a"])["f1"], 0)

    def test_predictions_cannot_cite_unseen_evidence(self):
        c = dataset()["cases"][0]
        pred = copy.deepcopy(c["gold"])
        pred["evidence_ids"].append("invented")
        m = lab.score_prediction(c, pred, [])
        self.assertIn("invented", m["invalid_citations"])
        self.assertTrue(m["root_hit"])
        self.assertEqual(m["citations"]["fp"], 1)

    def test_thirty_repeats_do_not_create_independent_events(self):
        pairs = [{"cluster_id": "same-incident", "baseline": {"metrics": {"symptoms": {"f1": .2}}},
                  "jev": {"metrics": {"symptoms": {"f1": .8}}}} for _ in range(30)]
        result = lab.paired_ci(pairs, "f1", 17)
        self.assertEqual(result["clusters"], 1)
        self.assertIsNone(result["ci95"])
        self.assertAlmostEqual(result["delta"], .6)

    def test_cluster_equal_weight_and_seeded_interval(self):
        pairs = []
        for n in range(5):
            for _ in range(30 if n == 0 else 1):
                pairs.append({"cluster_id": str(n), "baseline": {"metrics": {"symptoms": {"f1": 0}}},
                              "jev": {"metrics": {"symptoms": {"f1": 1 if n == 0 else 0}}}})
        result = lab.paired_ci(pairs, "f1", 17)
        self.assertAlmostEqual(result["delta"], .2)
        self.assertEqual(result, lab.paired_ci(pairs, "f1", 17))
        self.assertIsNotNone(result["ci95"])


class ProviderTests(unittest.TestCase):
    def arm(self, provider, arm="jev"):
        return lab.run_arm(dataset()["cases"][0], arm, lab.options({}), provider, threading.Event(), time.monotonic()+60)

    def test_real_contract_projection_and_billing(self):
        requests = []
        def transport(url, key, body, timeout):
            kind = "jev" if "systemone" in url else "llm"
            requests.append(body)
            result = lab.Provider.demo(kind, body)
            result["model"] = "jev-1.13.0" if kind == "jev" else "test-model"
            result["usage"] = {"input_tokens": 100, "output_tokens": 5} if kind == "jev" else {
                "prompt_tokens": 100, "completion_tokens": 10, "prompt_tokens_details": {"cached_tokens": 50}}
            return result
        result = self.arm(lab.Provider(cfg(), "live", transport))
        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(len(result["calls"]), 2)
        self.assertAlmostEqual(result["calls"][1]["cost_usd"], .000075)
        self.assertNotIn('"gold"', lab.encoded(requests).decode())
        self.assertEqual(requests[1]["messages"][0]["content"], lab.SYSTEM)
        for key, question in requests[0]["questions"].items():
            self.assertEqual(question["instructions"]["candidate_id"], key)

    def test_jev_missing_probability_and_out_of_range_fail_no_fallback(self):
        for mode in ("missing", "nan", "negative", "model"):
            def transport(url, key, body, timeout):
                result = lab.Provider.demo("jev", body)
                result["model"] = "other" if mode == "model" else "jev-1.13.0"
                first = next(iter(result["answers"]))
                if mode == "missing":
                    del result["answers"][first]
                elif mode == "nan":
                    result["answers"][first]["noul"] = float("nan")
                elif mode == "negative":
                    result["answers"][first]["noul"] = -.1
                return result
            result = self.arm(lab.Provider(cfg(), "live", transport))
            self.assertEqual(result["status"], "FAILED")
            self.assertEqual(len(result["calls"]), 1)
            self.assertIsNone(result["selection"])

    def test_missing_usage_is_not_zero_or_savings(self):
        result = self.arm(lab.Provider(cfg(), "live", lambda *args: lab.Provider.demo("llm", args[2])), "baseline")
        self.assertEqual(result["status"], "SUCCESS")
        self.assertIsNone(result["calls"][0]["input_tokens"])
        self.assertIsNone(result["calls"][0]["cost_usd"])

    def test_bad_llm_json_keeps_billed_usage_and_fails(self):
        result = self.arm(lab.Provider(cfg(), "live", lambda *args: {
            "usage": {"prompt_tokens": 10, "completion_tokens": 2}, "choices": [{"message": {"content": "not json"}}]}), "baseline")
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["calls"][0]["input_tokens"], 10)

    def test_cancelled_or_deadline_never_calls_provider(self):
        stop = threading.Event()
        stop.set()
        provider = lab.Provider(cfg(), "live", lambda *args: self.fail("should not call"))
        for event, deadline in ((stop, time.monotonic()+60), (threading.Event(), time.monotonic()-1)):
            result = lab.run_arm(dataset()["cases"][0], "baseline", lab.options({}), provider, event, deadline)
            self.assertEqual(result["status"], "FAILED")
            self.assertEqual(result["calls"], [])


class RunnerTests(unittest.TestCase):
    def test_thirty_batches_and_immutable_snapshot_with_inconclusive_demo(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = lab.Lab(directory, cfg())
            d = dataset()
            original = lab.digest(d)
            run_id = runner.start({"dataset": d, "options": {"rounds": 30}})
            d["version"] = "changed-after-start"
            runner.worker.join(30)
            self.assertFalse(runner.worker.is_alive())
            run = runner.get(run_id)
            self.assertEqual(run["status"], "COMPLETED")
            self.assertEqual(run["dataset_hash"], original)
            self.assertEqual(len(run["pairs"]), 180)
            self.assertEqual(len({(p["batch"], p["case_id"]) for p in run["pairs"]}), 180)
            self.assertEqual(run["summary"]["valid_pairs"], 180)
            self.assertEqual(run["summary"]["verdict"], "INCONCLUSIVE")
            self.assertIsNone(run["summary"]["arms"]["jev"]["input_tokens"])
            self.assertEqual(sum(len(p[a]["calls"]) for p in run["pairs"] for a in ("baseline", "jev")), 540)
            self.assertNotIn("secret-jev", lab.encoded(run).decode())
            # Missing pairs must not benefit by reducing the planned denominator.
            run["mode"], run["dataset"]["synthetic"] = "live", False
            self.assertEqual(lab.summarize(run)["verdict"], "IMPROVED")
            for p in run["pairs"]:
                p["baseline"], p["jev"] = p["jev"], p["baseline"]
            self.assertEqual(lab.summarize(run)["verdict"], "REGRESSED")
            for p in run["pairs"]:
                p["jev"] = copy.deepcopy(p["baseline"])
            self.assertEqual(lab.summarize(run)["verdict"], "NO_CLEAR_GAIN")
            run["pairs"].pop()
            self.assertEqual(lab.summarize(run)["verdict"], "INCONCLUSIVE")

    def test_cancellation_conflicting_start_and_restart(self):
        entered, release = threading.Event(), threading.Event()
        original = lab.Provider.demo
        def slow(kind, payload):
            entered.set()
            release.wait(3)
            return original(kind, payload)
        with tempfile.TemporaryDirectory() as directory, patch.object(lab.Provider, "demo", side_effect=slow):
            runner = lab.Lab(directory, cfg())
            run_id = runner.start({"dataset": dataset()})
            self.assertTrue(entered.wait(3))
            with self.assertRaisesRegex(ValueError, "已有实验"):
                runner.start({"dataset": dataset()})
            runner.cancel(run_id)
            release.set()
            runner.worker.join(5)
            run = runner.get(run_id)
            self.assertEqual(run["status"], "CANCELLED")
            self.assertEqual(run["summary"]["verdict"], "INCONCLUSIVE")
            run.pop("summary")
            run["status"] = "RUNNING"
            runner.save(run)
            restarted = lab.Lab(directory, cfg())
            self.assertEqual(restarted.get(run_id)["status"], "INTERRUPTED")
            self.assertIsNone(restarted.worker)

    def test_missing_live_credentials_fails_before_run_created(self):
        config = cfg()
        config["jev_key"] = ""
        with tempfile.TemporaryDirectory() as directory:
            runner = lab.Lab(directory, config)
            with self.assertRaisesRegex(ValueError, "环境配置"):
                runner.start({"dataset": dataset(), "mode": "live"})
            self.assertEqual(runner.listing(), [])

    def test_repeated_provider_failure_halts_without_hiding_missing_pairs(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(lab.Provider, "demo", side_effect=RuntimeError("PROVIDER_HTTP_429")):
            runner = lab.Lab(directory, cfg())
            run_id = runner.start({"dataset": dataset()})
            runner.worker.join(5)
            run = runner.get(run_id)
            self.assertEqual(run["status"], "HALTED_ERRORS")
            self.assertEqual(run["planned_pairs"], 180)
            self.assertEqual(run["summary"]["valid_pairs"], 0)
            self.assertEqual(run["summary"]["verdict"], "INCONCLUSIVE")

    def test_in_flight_persistence_never_reports_zero_cost(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = lab.Lab(directory, cfg())
            run_id = runner.start({"dataset": dataset(), "options": {"rounds": 1}})
            runner.worker.join(5)
            run = runner.get(run_id)
            run["status"] = "INTERRUPTED"
            run["pairs"][0]["jev"] = {"status": "IN_FLIGHT", "calls": []}
            s = lab.summarize(run)
            self.assertEqual(s["arms"]["jev"]["incomplete_attempts"], 1)
            self.assertIsNone(s["arms"]["jev"]["cost_usd"])
            self.assertIsNone(s["savings"]["total_tokens"])

    def test_uuid_path_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                lab.Lab(directory, cfg()).get("../../outside")


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.runner = lab.Lab(self.directory.name, cfg())
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), lab.handler_for(self.runner, "csrf-token"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.runner.stop.set()
        if self.runner.worker:
            self.runner.worker.join(5)
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(3)
        self.directory.cleanup()

    def test_page_api_and_no_keys_in_config(self):
        for path in ("/", "/app.js", "/style.css", "/api/example"):
            with request.urlopen(self.base + path) as r:
                self.assertEqual(r.status, 200)
                self.assertGreater(len(r.read()), 100)
        with request.urlopen(self.base + "/api/config") as r:
            config = r.read().decode()
            self.assertNotIn("secret-jev", config)
            self.assertIn("csrf-token", config)

    def test_cross_site_and_missing_csrf_cannot_launch(self):
        for headers in ({"Origin": "https://evil.test", "X-Lab-Token": "csrf-token"}, {}, {"Host": "evil.test"}):
            req = request.Request(self.base + "/api/runs", data=b"{}", headers={"Content-Type": "application/json", **headers})
            with self.assertRaises(error.HTTPError) as ex:
                request.urlopen(req)
            self.assertEqual(ex.exception.code, 400)
            ex.exception.close()
        self.assertEqual(self.runner.listing(), [])

    def test_launch_poll_export_and_cancel_over_http(self):
        req = request.Request(self.base + "/api/runs", data=lab.encoded({"dataset": dataset(), "options": {"rounds": 1}}),
                              headers={"Content-Type": "application/json", "X-Lab-Token": "csrf-token"})
        with request.urlopen(req) as r:
            self.assertEqual(r.status, 202)
            run_id = json.load(r)["id"]
        self.runner.worker.join(10)
        with request.urlopen(self.base + "/api/runs/" + run_id) as r:
            self.assertEqual(json.load(r)["summary"]["valid_pairs"], 6)
        req = request.Request(self.base + "/api/runs/" + run_id + "/cancel", data=b"{}",
                              headers={"Content-Type": "application/json", "X-Lab-Token": "csrf-token"})
        with request.urlopen(req) as r:
            self.assertEqual(json.load(r)["status"], "COMPLETED")


if __name__ == "__main__":
    unittest.main(verbosity=2)
