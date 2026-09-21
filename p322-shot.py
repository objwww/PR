# -*- coding: utf-8 -*-
# 第 14 轮截图：全站小节标题统一验证（配置中心/演练/通知）
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Demo#0917'
OUT = Path(r'E:\kimiCode\docs\测试证据\全站标题统一-20260917')
OUT.mkdir(parents=True, exist_ok=True)
report = {'steps': [], 'consoleErrors': []}

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.on('console', lambda m: report['consoleErrors'].append(m.text[:120]) if m.type == 'error' else None)

    page.goto(BASE + '/login', wait_until='networkidle')
    page.fill('input[type=text]', 'operator')
    page.fill('input[type=password]', PW)
    page.click('button.login-btn')
    for _ in range(12):
        time.sleep(0.8)
        if '/login' not in page.url:
            break

    # 配置中心：sec-title 左条（调查路由页签内三个 sec-title）
    page.goto(BASE + '/config', wait_until='networkidle')
    page.click('.el-tabs__item:has-text("调查路由")')
    time.sleep(2)
    styled = page.locator('h3.sec-title').first.evaluate(
        "el => getComputedStyle(el).borderLeftWidth + '|' + getComputedStyle(el).borderLeftColor")
    report['steps'].append(f"config_sec_title_border={styled}")
    page.screenshot(path=str(OUT / '81-配置中心-标题统一.png'), full_page=False)

    # 演练页：zone-title 左条
    page.goto(BASE + '/drills', wait_until='networkidle')
    time.sleep(2.5)
    if page.locator('h3.zone-title').count() > 0:
        styled2 = page.locator('h3.zone-title').first.evaluate(
            "el => getComputedStyle(el).borderLeftWidth + '|' + getComputedStyle(el).borderLeftColor")
        report['steps'].append(f"drills_zone_title_border={styled2}")
    page.screenshot(path=str(OUT / '82-演练页-标题统一与摘要色.png'), full_page=False)

    # 通知页：silence-head h3 左条
    page.goto(BASE + '/notifications', wait_until='networkidle')
    time.sleep(2)
    if page.locator('.silence-head h3').count() > 0:
        styled3 = page.locator('.silence-head h3').first.evaluate(
            "el => getComputedStyle(el).borderLeftWidth")
        report['steps'].append(f"notifications_h3_border={styled3}")

    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
