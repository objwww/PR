# -*- coding: utf-8 -*-
# 3.16 Prompt 工作台：195 真机截图（隧道 18090→8090；临时口令 Tmp#p16-0916）
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\prompt工作台-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#p16-0916")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_timeout(1800)
    if "/login" in page.url:
        page.wait_for_timeout(1500)  # 会话竞态兜底：再等一次
    assert "/login" not in page.url, "登录失败（口令或会话竞态）"
    page.goto(BASE + "/prompt")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2000)
    # 48 首屏：统计 + 生效能力版本 + 版本列表
    page.screenshot(path=OUT + r"\48-prompt工作台-首屏.png", full_page=False)
    # 49 选中第一个版本 → 加载全文（.list-panel 限定版本列表，避免命中生效能力表）
    page.locator(".list-panel .el-table__row").first.click()
    page.wait_for_timeout(1000)
    page.get_by_role("button", name="加载全文").click()
    page.wait_for_timeout(1200)
    page.screenshot(path=OUT + r"\49-prompt工作台-版本正文.png", full_page=False)
    # 50 勾选基准（第二行）+ 打开对比弹窗
    page.locator(".list-panel .el-table__row").nth(1).locator(".cmp-box").evaluate("el => el.click()")
    page.wait_for_timeout(400)
    page.get_by_role("button", name="与基准").click()
    page.wait_for_timeout(1500)
    page.screenshot(path=OUT + r"\50-prompt工作台-版本逐行对比.png", full_page=False)
    page.keyboard.press("Escape")
    # 51 试跑区 + 生效能力版本（滚动到底）
    page.keyboard.press("End")
    page.wait_for_timeout(600)
    page.screenshot(path=OUT + r"\51-prompt工作台-试跑区.png", full_page=False)
    browser.close()
print("OK: 4 张截图已存", OUT)
