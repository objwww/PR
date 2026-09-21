# -*- coding: utf-8 -*-
import json, time
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Tmp#p316-0917'
report = {'steps': [], 'console': [], 'pageErrors': []}

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.on('console', lambda m: report['console'].append(f"{m.type}: {m.text[:200]}") if m.type in ('error', 'warning') else None)
    page.on('pageerror', lambda e: report['pageErrors'].append(str(e)[:300]))

    page.goto(BASE + '/login', wait_until='networkidle')
    page.fill('input[type=text]', 'operator')
    page.fill('input[type=password]', PW)
    page.click('button.login-btn')
    for _ in range(12):
        time.sleep(0.8)
        if '/login' not in page.url:
            break
    report['steps'].append(f"after_login_url={page.url}")
    page.goto(BASE + '/monitor', wait_until='networkidle')
    time.sleep(4)
    report['steps'].append(f"monitor_url={page.url}")
    report['steps'].append(f"has_monitor_title={'监控' in page.content()}")
    report['steps'].append(f"has_latency_text={'分层延迟' in page.content()}")
    report['steps'].append(f"body_snippet={page.locator('body').inner_text()[:300]!r}")
    page.screenshot(path=r'E:\kimiCode\p316-diag.png', full_page=False)
    browser.close()

print(json.dumps(report, ensure_ascii=False, indent=1))
