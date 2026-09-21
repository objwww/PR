# -*- coding: utf-8 -*-
import json, time
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Demo#0917'
OUT = r'E:\kimiCode\docs\测试证据\调查路由可视化-20260917'
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
    page.goto(BASE + '/config', wait_until='networkidle')
    time.sleep(3)
    bars = page.locator('.bar').count()
    report['steps'].append(f'category_bars={bars}')
    page.click('.el-tabs__item:has-text("调查路由")')
    time.sleep(2)
    page.locator('.el-tabs__content').last.evaluate('el => el.scrollTop = el.scrollHeight')
    time.sleep(0.5)
    page.screenshot(path=OUT + r'\77-配置中心-决策流水.png')
    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
