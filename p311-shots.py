# -*- coding: utf-8 -*-
# 3.11 评测中心增强：195 真机截图（隧道 18090→8090；临时口令 Tmp#p311-0916）
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\评测中心增强-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#p311-0916")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_timeout(1800)
    assert "/login" not in page.url, "登录失败"
    # 56 数据集分层页
    page.goto(BASE + "/eval/datasets")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2000)
    page.screenshot(path=OUT + r"\56-评测中心-数据集分层打标.png", full_page=False)
    # 57 实验治理列 + 一键重跑
    page.goto(BASE + "/eval/runs")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2500)
    page.screenshot(path=OUT + r"\57-评测中心-实验治理列与重跑.png", full_page=False)
    browser.close()
print("OK: 2 张截图已存", OUT)
