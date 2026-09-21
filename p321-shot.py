# -*- coding: utf-8 -*-
# 第 13 轮截图：调查路由可视化 + 详情页等待卡 + 美化效果
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Demo#0917'
OUT = Path(r'E:\kimiCode\docs\测试证据\调查路由可视化-20260917')
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

    # 1) 配置中心 → 调查路由
    page.goto(BASE + '/config', wait_until='networkidle')
    page.click('.el-tabs__item:has-text("调查路由")')
    ok = False
    for _ in range(12):
        time.sleep(1)
        if page.locator('h3.sec-title', has_text='等待放量的事件').count() > 0:
            ok = True
            break
    report['steps'].append(f"routing_tab={ok}")
    assert ok, '调查路由页签未渲染'
    waiting_rows = page.locator('.el-table__row').count()
    report['steps'].append(f"table_rows={waiting_rows}")
    page.screenshot(path=str(OUT / '76-配置中心-调查路由-等待清单与放量.png'), full_page=False)
    page.locator('.el-tabs__content').last.evaluate('el => el.scrollTop = el.scrollHeight')
    time.sleep(0.5)
    page.screenshot(path=str(OUT / '77-配置中心-决策流水.png'), full_page=False)

    # 2) 详情页：WAITING_CAPABILITY 事件的等待原因卡
    inc = page.evaluate("fetch('/api/v1/routing/overview').then(r=>r.json()).then(d=>d.waiting[0]?.incidentId)")
    report['steps'].append(f"waiting_inc={inc}")
    page.goto(f'{BASE}/alerts/{inc}', wait_until='networkidle')
    ok2 = False
    for _ in range(12):
        time.sleep(1)
        if page.locator('.el-alert__title', has_text='AI 尚未自动调查').count() > 0:
            ok2 = True
            break
    report['steps'].append(f"waiting_card={ok2}")
    assert ok2, '等待原因卡未渲染'
    time.sleep(0.5)
    page.screenshot(path=str(OUT / '78-告警详情-等待原因卡与发起入口.png'), full_page=False)

    # 3) 点击「尝试立即发起」→ 诚实拒绝解释框
    page.click('button:has-text("尝试立即发起根因分析")')
    page.wait_for_selector('.el-message-box', timeout=10000)
    time.sleep(0.6)
    box_text = page.locator('.el-message-box__message').inner_text()
    report['steps'].append(f"reject_box={box_text[:60]}")
    page.screenshot(path=str(OUT / '79-告警详情-路由未意愿诚实解释.png'), full_page=False)
    page.click('.el-message-box__btns button')

    # 4) 美化效果：复盘页小节左条
    page.goto(BASE + '/postmortems', wait_until='networkidle')
    time.sleep(2)
    if page.locator('.pm-item').count() > 0:
        page.locator('.pm-item').first.click()
        time.sleep(1.2)
        page.screenshot(path=str(OUT / '80-复盘页-小节标题美化.png'), full_page=False)

    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
