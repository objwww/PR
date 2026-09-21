# -*- coding: utf-8 -*-
# 3.4 AI 诊断页：195 真机逐页签截图（隧道 18090→8090；临时口令 Tmp#diag34-0916）
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\ai诊断页-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#diag34-0916")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1500)
    # 进 AI 诊断页
    page.goto(BASE + "/diag")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2000)
    # 45 首屏：统计行 + 事件上下文 + 聊天流历史
    page.screenshot(path=OUT + r"\45-ai诊断页-首屏-统计上下文历史.png", full_page=False)
    # 46 点一个快捷问（实时写路径）
    page.get_by_role("button", name="这个告警影响什么？").click()
    page.wait_for_timeout(2500)
    page.screenshot(path=OUT + r"\46-ai诊断页-快捷问实时问答.png", full_page=False)
    # 47 自由问输入 + 滚动看历史
    page.get_by_placeholder("自由追问（接真模型，仅基于该事件已核实事实作答）").fill("这次告警涉及哪些服务？")
    page.screenshot(path=OUT + r"\47-ai诊断页-自由问输入与历史流.png", full_page=False)
    browser.close()
print("OK: 3 张截图已存", OUT)
