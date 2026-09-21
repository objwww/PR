"""Optional browser acceptance test (requires locally installed Playwright/Chromium).
Does not call a model; writes screenshots and DEMO results under var/jev-lab-browser.
"""
import json
from pathlib import Path
import tempfile
import threading
from http.server import ThreadingHTTPServer

from playwright.sync_api import sync_playwright, expect
import lab


def main():
    artifacts = lab.HERE.parent.parent / "var" / "jev-lab-browser"
    artifacts.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        cfg = {"jev_url": "https://api.typesafe.ai/v1/systemone", "jev_model": "jev-1.13.0", "jev_key": "",
               "llm_url": "", "llm_model": "", "llm_key": "", "prices": {}}
        runner = lab.Lab(directory, cfg)
        server = ThreadingHTTPServer(("127.0.0.1", 0), lab.handler_for(runner, "browser-test-token"))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch(headless=True)
                page = browser.new_page(viewport={"width": 1440, "height": 1000})
                errors = []
                page.on("pageerror", lambda ex: errors.append(str(ex)))
                page.on("console", lambda msg: errors.append(msg.text) if msg.type == "error" else None)
                page.goto(f"http://127.0.0.1:{server.server_port}")
                page.get_by_role("button", name="载入合成示例").click()
                page.get_by_role("button", name="运行 30 批配对实验").click()
                expect(page.locator("#statusText")).to_have_text("运行完成", timeout=60000)
                assert "180 / 180" in page.locator("#progressText").inner_text()
                assert "暂不能判定真实提升" in page.locator("#verdict").inner_text()
                assert page.locator("#details tr").count() == 180
                page.locator("#details tr").first.click()
                assert '"selected_ids"' in page.locator("#auditText").inner_text()
                with page.expect_download() as download:
                    page.get_by_role("button", name="导出完整 JSON").click()
                download.value.save_as(artifacts / "demo-30-batches.json")
                result = json.loads((artifacts / "demo-30-batches.json").read_text(encoding="utf-8"))
                assert result["summary"]["valid_pairs"] == 180
                page.locator("#audit summary").click()
                page.screenshot(path=str(artifacts / "desktop.png"), full_page=True)
                page.reload()
                expect(page.locator("#statusText")).to_have_text("运行完成")
                page.set_viewport_size({"width": 390, "height": 844})
                page.screenshot(path=str(artifacts / "mobile.png"), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
                assert not errors, errors
                browser.close()
                print(json.dumps({"browser": "PASS", "pairs": result["summary"]["valid_pairs"],
                                  "verdict": result["summary"]["verdict"], "console_errors": errors,
                                  "artifacts": str(artifacts)}, ensure_ascii=False))
        finally:
            runner.stop.set()
            if runner.worker:
                runner.worker.join(35)
            server.shutdown()
            server.server_close()
            thread.join(3)


if __name__ == "__main__":
    main()
