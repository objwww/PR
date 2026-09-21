# -*- coding: utf-8 -*-
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#p312-0916")
    resp = page.request.post(BASE + "/api/auth/csrf")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_timeout(2500)
    print("url=", page.url)
    print("csrf_status=", resp.status)
    # 用 API 直接验证口令是否有效
    import json
    t = page.evaluate("() => document.cookie")
    print("cookies=", t[:120])
    r2 = page.request.post(BASE + "/api/auth/login", data={"username": "operator", "password": "Tmp#p312-0916"})
    print("api_login=", r2.status, r2.text()[:120])
    page.screenshot(path=r"E:\kimiCode\docs\测试证据\演练增强-20260916\_dbg.png")
    browser.close()
