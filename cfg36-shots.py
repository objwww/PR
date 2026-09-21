# -*- coding: utf-8 -*-
# 3.6 配置中心：195 真机四页签截图（隧道 18090→8090；临时口令 Tmp#cfg36-0916）
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:18090"
OUT = r"E:\kimiCode\docs\测试证据\配置中心-20260916"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    page = browser.new_page(viewport={"width": 1600, "height": 950})
    page.goto(BASE + "/")
    page.wait_for_load_state("networkidle")
    page.get_by_placeholder("请输入账号").fill("operator")
    page.get_by_placeholder("请输入密码").fill("Tmp#cfg36-0916")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_timeout(1800)
    assert "/login" not in page.url, "登录失败"
    page.goto(BASE + "/config")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2000)
    # 52 告警分类页签
    page.screenshot(path=OUT + r"\52-配置中心-告警分类分布.png", full_page=False)
    # 53 接入管理页签
    page.get_by_role("tab", name="接入管理").click()
    page.wait_for_timeout(1200)
    page.screenshot(path=OUT + r"\53-配置中心-接入管理.png", full_page=False)
    # 54 通知渠道页签
    page.get_by_role("tab", name="通知渠道").click()
    page.wait_for_timeout(1200)
    page.screenshot(path=OUT + r"\54-配置中心-通知渠道.png", full_page=False)
    # 55 权限与角色页签
    page.get_by_role("tab", name="权限与角色").click()
    page.wait_for_timeout(1200)
    page.screenshot(path=OUT + r"\55-配置中心-权限与角色.png", full_page=False)
    browser.close()
print("OK: 4 张截图已存", OUT)
