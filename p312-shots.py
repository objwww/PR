# -*- coding: utf-8 -*-
# 3.12 演练增强：195 真机截图（隧道 18090→8090；临时口令 Tmp#p312-0916）
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\演练增强-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#p312-0916")
    page.get_by_role("button", name="登 录").click()
    for _ in range(10):
        page.wait_for_timeout(800)
        if "/login" not in page.url:
            break
    assert "/login" not in page.url, "登录失败"
    page.goto(BASE + "/drills")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2000)
    # 58 矩阵 + 四段复盘首屏
    page.screenshot(path=OUT + r"\58-演练增强-韧性覆盖矩阵.png", full_page=False)
    # 59 滚动到四段复盘 + 演练列表
    page.locator(".four-zone").scroll_into_view_if_needed()
    page.wait_for_timeout(500)
    page.screenshot(path=OUT + r"\59-演练增强-四段复盘与列表.png", full_page=False)
    # 60 故障模式库（模板目录）——滚动回矩阵顶部并翻页看全部场景
    page.keyboard.press("Home")
    page.wait_for_timeout(500)
    page.evaluate("window.scrollTo(0, 300)")
    page.wait_for_timeout(400)
    page.screenshot(path=OUT + r"\60-演练增强-故障模式库与矩阵中段.png", full_page=False)
    browser.close()
print("OK: 3 张截图已存", OUT)
