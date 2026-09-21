# -*- coding: utf-8 -*-
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\prompt工作台-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    errors = []
    page.on("console", lambda m: errors.append("console:" + m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: errors.append("page:" + str(e)))
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#p16-0916")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_timeout(1800)
    page.goto(BASE + "/prompt")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2500)
    try:
        page.locator(".el-table__row").first.click(timeout=5000)
    except Exception as e:
        errors.append("click:" + str(e)[:200])
    page.wait_for_timeout(1500)
    print("rows=", page.locator(".el-table__row").count())
    print("btn=", page.locator('button:has-text("加载全文")').count())
    print("ERRORS:", errors[:6])
    page.screenshot(path=OUT + r"\_debug4.png", full_page=False)
    browser.close()
