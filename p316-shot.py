# -*- coding: utf-8 -*-
# 3.16 监控页三区块截图脚本（195 真机经隧道 18090，msedge 无头）
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Tmp#p316-0917'
OUT = Path(r'E:\kimiCode\docs\测试证据\监控三区块-20260917')
OUT.mkdir(parents=True, exist_ok=True)
report = {'steps': [], 'consoleErrors': []}

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.on('console', lambda m: report['consoleErrors'].append(m.text) if m.type == 'error' else None)
    page.on('response', lambda r: report['consoleErrors'].append(f"[resp] {r.status} {r.url}") if r.status >= 400 else None)

    # 1) 登录
    page.goto(BASE + '/login', wait_until='networkidle')
    page.fill('input[type=text]', 'operator')
    page.fill('input[type=password]', PW)
    page.click('button.login-btn')
    for _ in range(12):
        time.sleep(0.8)
        if '/login' not in page.url:
            break
    report['steps'].append(f"login url={page.url}")

    # 2) 监控页：等三个新区块出数据
    page.goto(BASE + '/monitor', wait_until='networkidle')
    ok = False
    for _ in range(15):
        time.sleep(1)
        if page.locator('.panel-title', has_text='分层延迟').count() > 0 \
           and page.locator('.panel-title', has_text='风险审计').count() > 0:
            ok = True
            break
    report['steps'].append(f"blocks_visible={ok}")
    assert ok, '三区块未渲染'
    time.sleep(2)
    # 断言四层行与延迟表
    rows = page.locator('.cols .el-table__row').count()
    cost_total = page.locator('text=模型总成本').count()
    risk_rows = page.locator('.monitor-page .card.panel .el-table__row').count()
    report['steps'].append(f"latency_rows={rows} cost_total_block={cost_total} table_rows_total={risk_rows}")
    assert 'Guardian' in page.content(), '风险审计应含 Guardian 事件'

    # 3) 截图：延迟+成本双列区
    page.locator('.cols').last.scroll_into_view_if_needed()
    time.sleep(0.5)
    page.screenshot(path=str(OUT / '66-监控-分层延迟与成本归因.png'), full_page=False)

    # 4) 截图：风险审计流（含 run 锚）
    panels = page.locator('.card.panel')
    for i in range(panels.count()):
        if panels.nth(i).locator('text=风险审计').count() > 0:
            panels.nth(i).scroll_into_view_if_needed()
            break
    time.sleep(0.5)
    page.screenshot(path=str(OUT / '67-监控-风险审计流.png'), full_page=False)

    # 5) 全页俯瞰
    page.evaluate('window.scrollTo(0, 0)')
    time.sleep(0.4)
    page.screenshot(path=str(OUT / '68-监控-全页俯瞰.png'), full_page=True)

    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
