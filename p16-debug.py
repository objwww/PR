# -*- coding: utf-8 -*-
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\prompt工作台-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    errors = []
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#p16-0916")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_load_state("networkidle")
    page.goto(BASE + "/prompt")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2500)
    print("errors=", errors[:5])
    print("rows=", page.locator(".el-table__row").count())
    page.screenshot(path=OUT + r"\_debug3.png", full_page=False)
    print("btn_count=", page.locator('button:has-text("加载全文")').count())
    print("rows=", page.locator(".el-table__row").count())
    print("errors=", errors[:3])
    page.screenshot(path=OUT + r"\_debug2.png", full_page=False)
    browser.close()
