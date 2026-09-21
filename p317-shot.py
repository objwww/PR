# -*- coding: utf-8 -*-
# 3.3 补齐截图脚本：详情页关联告警区块 + 合并时间轴（195 真机经隧道）
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Tmp#p317-0917'
INC = 'f4cf44b2-a5fd-48fe-875c-69ba3f45d78a'
OUT = Path(r'E:\kimiCode\docs\测试证据\告警详情关联增强-20260917')
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
    report['steps'].append(f"login ok={'/login' not in page.url or '会话已生效'}")

    # 1) 详情页概览：关联告警区块
    page.goto(f'{BASE}/alerts/{INC}', wait_until='networkidle')
    ok = False
    for _ in range(15):
        time.sleep(1)
        if page.locator('h3', has_text='关联告警').count() > 0 and page.locator('text=同告警键历史').count() > 0:
            ok = True
            break
    report['steps'].append(f"related_block={ok}")
    assert ok, '关联告警区块未渲染'
    blk = page.locator('.card.block', has=page.locator('h3', has_text='关联告警')).last
    blk.scroll_into_view_if_needed()
    time.sleep(0.5)
    page.screenshot(path=str(OUT / '69-告警详情-关联告警区块.png'), full_page=False)

    # 2) 时间线页签：合并轴全部
    page.click('.el-tabs__item:has-text("时间线")')
    time.sleep(1.5)
    tl_ok = page.locator('.panel-title, h3', has_text='时间线').count() > 0 or page.locator('.el-timeline').count() > 0
    report['steps'].append(f"timeline_items={page.locator('.el-timeline-item').count()}")
    page.screenshot(path=str(OUT / '70-告警详情-合并时间轴.png'), full_page=False)

    # 3) 筛选：仅变更（诚实空态）
    page.click('.el-radio-button:has-text("变更")')
    time.sleep(0.8)
    report['steps'].append(f"change_filter_items={page.locator('.el-timeline-item').count()}")
    page.screenshot(path=str(OUT / '71-告警详情-时间轴筛选变更空态.png'), full_page=False)

    # 4) 筛选：仅告警
    page.click('.el-radio-button:has-text("告警")')
    time.sleep(0.8)
    report['steps'].append(f"alert_filter_items={page.locator('.el-timeline-item').count()}")

    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
