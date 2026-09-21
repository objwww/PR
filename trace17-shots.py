# -*- coding: utf-8 -*-
# 3.17 Trace 瀑布页签：195 真机（SSH 隧道 18090→8090）逐页签截图
# 样本 run = 786f1c55-d03c-48d0-acfd-0250b79b66d9（SUCCEEDED：12 模型 + 12 工具 + 1 任务尝试）
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
RUN = "786f1c55-d03c-48d0-acfd-0250b79b66d9"
OUT = r"E:\kimiCode\docs\测试证据\trace-调用链瀑布页签-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    # 登录
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#trace17-0916")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    # 直达调查详情
    page.goto(f"{BASE}/runs/{RUN}")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    # 01 摘要页签（基准上下文）
    page.get_by_role("button", name="摘要").click()
    page.wait_for_timeout(1200)
    page.screenshot(path=OUT + r"\40-调用链批-摘要页签基准.png", full_page=False)
    # 02 调用链页签首屏：汇总行 + 时间轴 + 任务尝试 + 模型调用
    page.get_by_role("button", name="调用链").click()
    page.wait_for_timeout(1800)
    page.screenshot(path=OUT + r"\41-调用链-瀑布首屏-汇总时间轴模型.png", full_page=False)
    # 03 展开首个模型调用行（明细 KV：模型/Token/费用）
    page.get_by_text("序 0", exact=False).first.click()
    page.wait_for_timeout(800)
    page.screenshot(path=OUT + r"\42-调用链-模型调用明细展开.png", full_page=False)
    # 04 滚到工具调用区 + 图例
    page.keyboard.press("End")
    page.wait_for_timeout(800)
    page.screenshot(path=OUT + r"\43-调用链-工具调用区与图例.png", full_page=False)
    # 05 全页长图（完整瀑布证据）
    page.keyboard.press("Home")
    page.wait_for_timeout(600)
    page.screenshot(path=OUT + r"\44-调用链-全页长图.png", full_page=True)
    browser.close()
print("OK: 5 张截图已存", OUT)
