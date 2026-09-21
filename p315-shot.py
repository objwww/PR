# -*- coding: utf-8 -*-
# 3.15 复盘整改项截图脚本（195 真机经隧道 18090，msedge 无头）
import json, sys, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Tmp#p315-0916'
OUT = Path(r'E:\kimiCode\docs\测试证据\复盘整改项-20260916')
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

    # 2) 进入复盘页，选第一条已解决事故
    page.goto(BASE + '/postmortems', wait_until='networkidle')
    page.wait_for_selector('.pm-item', timeout=15000)
    page.locator('.pm-item').first.click()
    page.wait_for_selector('h3:has-text("整改项清单")', timeout=15000)
    time.sleep(1.0)
    badge = page.locator('h3:has-text("整改项清单") .el-tag').inner_text()
    rows = page.locator('.ai-row').count()
    report['steps'].append(f"badge_before={badge} rows={rows}")
    assert '整改中' in badge and '1/2' in badge, f"期望 整改中 1/2，实得 {badge}"
    page.screenshot(path=str(OUT / '63-复盘-整改项清单-整改中.png'), full_page=True)

    # 3) 勾选未闭环项 → 派生闭环达成
    row2 = page.locator('.ai-row', has_text='inventory-service 增加熔断兜底')
    row2.locator('.el-checkbox').click()
    page.wait_for_selector('.el-tag:has-text("复盘已闭环")', timeout=10000)
    time.sleep(0.8)
    badge_closed = page.locator('h3:has-text("整改项清单") .el-tag').inner_text()
    report['steps'].append(f"badge_closed={badge_closed}")
    assert badge_closed.strip() == '复盘已闭环', f"期望 复盘已闭环，实得 {badge_closed}"
    page.screenshot(path=str(OUT / '64-复盘-闭环达成.png'), full_page=True)

    # 4) 复原到 1/2 状态（保持数据真实 mixed 态供后续验收）
    row2.locator('.el-checkbox').click()
    page.wait_for_selector('.el-tag:has-text("整改中")', timeout=10000)
    time.sleep(0.6)
    badge_restored = page.locator('h3:has-text("整改项清单") .el-tag').inner_text()
    report['steps'].append(f"badge_restored={badge_restored}")

    # 5) 生成 ITSM 工单草稿（真源汇编落库），工单清单区出真数据
    page.click('button:has-text("生成工单草稿")')
    page.wait_for_selector('.detail .cell-sub:has-text("—— DRAFT")', timeout=15000)
    time.sleep(0.8)
    ticket_row = page.locator('.detail .cell-sub', has_text='—— DRAFT').first.inner_text()
    report['steps'].append(f"ticket_row={ticket_row.strip()[:60]}")
    page.screenshot(path=str(OUT / '65-复盘-工单草稿与整改项.png'), full_page=True)

    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
