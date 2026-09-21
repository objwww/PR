# -*- coding: utf-8 -*-
# 3.13 服务目录补强：195 真机截图（隧道 18090→8090；临时口令 Tmp#p313-0916）
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\服务目录补强-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#p313-0916")
    page.get_by_role("button", name="登 录").click()
    for _ in range(10):
        page.wait_for_timeout(800)
        if "/login" not in page.url:
            break
    assert "/login" not in page.url, "登录失败"
    page.goto(BASE + "/catalog")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2000)
    # 61 目录全量（含 30 天事件/MTTR/负责人列）
    page.screenshot(path=OUT + r"\61-服务目录-全量清单.png", full_page=False)
    # 62 打开负责人登记弹窗
    page.locator(".el-table__row").first.get_by_role("button", name="负责人").click()
    page.wait_for_timeout(800)
    page.screenshot(path=OUT + r"\62-服务目录-负责人登记弹窗.png", full_page=False)
    browser.close()
print("OK: 2 张截图已存", OUT)
