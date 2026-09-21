# -*- coding: utf-8 -*-
# 第 12 轮截图：监控性能概览环比卡（195 真机经隧道）
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Tmp#p319-0917'
OUT = Path(r'E:\kimiCode\docs\测试证据\监控环比双窗-20260917')
OUT.mkdir(parents=True, exist_ok=True)
report = {'steps': [], 'consoleErrors': []}

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.on('console', lambda m: report['consoleErrors'].append(m.text) if m.type == 'error' else None)
    page.on('response', lambda r: report['consoleErrors'].append(f"[resp] {r.status} {r.url}") if r.status >= 400 else None)

    page.goto(BASE + '/login', wait_until='networkidle')
    page.fill('input[type=text]', 'operator')
    page.fill('input[type=password]', PW)
    page.click('button.login-btn')
    for _ in range(12):
        time.sleep(0.8)
        if '/login' not in page.url:
            break

    # 监控页：性能概览环比表
    page.goto(BASE + '/monitor', wait_until='networkidle')
    ok = False
    for _ in range(15):
        time.sleep(1)
        if page.locator('.panel-title', has_text='性能概览').count() > 0 \
           and page.locator('.cols .el-table__row', has_text='调用次数').count() > 0:
            ok = True
            break
    report['steps'].append(f"perf_table={ok}")
    assert ok, '环比表未渲染'
    rows = page.locator('.cols .el-table__row').count()
    report['steps'].append(f"perf_rows={rows}")
    assert rows >= 5, f'环比行数不足：{rows}'
    card = page.locator('.card.panel', has=page.locator('.panel-title', has_text='性能概览'))
    card.scroll_into_view_if_needed()
    time.sleep(0.5)
    page.screenshot(path=str(OUT / '75-监控-性能概览环比双窗.png'), full_page=False)

    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
