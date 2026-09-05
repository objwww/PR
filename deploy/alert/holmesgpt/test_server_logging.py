# -*- coding: utf-8 -*-
"""AM3 M3-27 源码守卫：server.py 日志绝不携带 ask 正文/secret/原始工具参数。

不 import server（其依赖 holmes 运行时，本机测试环境不可得）；对源码文本做
结构性断言——M3-27 验收「双侧日志 capture 断言」的 server 侧静态半边，动态
半边由 195 真栈 E2E-M3-03 承担。运行：python -m unittest test_server_logging -v
"""
import pathlib
import unittest

SERVER = pathlib.Path(__file__).with_name("server.py")


class ServerLoggingHygieneTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SERVER.read_text(encoding="utf-8")

    def test_no_ask_body_in_log_interpolation(self):
        self.assertNotIn("ask={chat_request.ask}", self.source)

    def test_safe_req_info_helper_exists_and_wired(self):
        self.assertIn("def _safe_req_info(", self.source)
        self.assertIn("req_info = _safe_req_info(chat_request, http_request)", self.source)

    def test_ask_only_as_size_and_short_digest(self):
        self.assertIn("hashlib.sha256", self.source)
        self.assertIn("ask_size=", self.source)
        self.assertIn("ask_sha256_16=", self.source)
        self.assertIn("[:16]", self.source)

    def test_run_attempt_correlation_headers_read(self):
        self.assertIn('"X-Run-Id"', self.source)
        self.assertIn('"X-Attempt-Id"', self.source)


if __name__ == "__main__":
    unittest.main()
