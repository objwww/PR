# -*- coding: utf-8 -*-
# 3.4 补强截图：诊断页回答评价（195 真机经隧道）
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'
PW = 'Tmp#p318-0917'
OUT = Path(r'E:\kimiCode\docs\测试证据\诊断评价采集-20260917')
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

    # 1) 诊断页：好评率卡 + 评价按钮在场
    page.goto(BASE + '/diag', wait_until='networkidle')
    ok = False
    for _ in range(15):
        time.sleep(1)
        if page.locator('.stat-label', has_text='回答好评率').count() > 0 \
           and page.locator('.fb-line button', has_text='有用').count() > 0:
            ok = True
            break
    report['steps'].append(f"blocks={ok}")
    assert ok, '评价区块未渲染'
    rate_before = page.locator('.stat-card', has=page.locator('.stat-label', has_text='回答好评率')).locator('.stat-num').inner_text()
    report['steps'].append(f"praise_before={rate_before}")
    page.locator('.fb-line').first.scroll_into_view_if_needed()
    time.sleep(0.4)
    page.screenshot(path=str(OUT / '72-诊断页-评价按钮与好评率.png'), full_page=False)

    # 2) 给第一条回答评「有用」→ 好评率联动
    page.locator('.fb-line button', has_text='有用').first.click()
    page.wait_for_selector('.el-tag:has-text("已评：有用")', timeout=10000)
    time.sleep(1.5)
    rate_after = page.locator('.stat-card', has=page.locator('.stat-label', has_text='回答好评率')).locator('.stat-num').inner_text()
    report['steps'].append(f"praise_after={rate_after}")
    page.screenshot(path=str(OUT / '73-诊断页-评有用成功联动好评率.png'), full_page=False)

    # 3) 第二条点「无用」展开原因选择（含原因下拉与提交按钮）
    rows = page.locator('.fb-line button', has_text='无用')
    if rows.count() > 1:
        rows.nth(1).click()
        page.wait_for_selector('.fb-down', timeout=8000)
        time.sleep(0.5)
        page.screenshot(path=str(OUT / '74-诊断页-无用原因展开.png'), full_page=False)
        # 收起不提交（保持数据为验收演示态）
        page.locator('.fb-down button', has_text='提交无用评价').click()
        page.wait_for_selector('.el-tag:has-text("已评：无用")', timeout=10000)
        time.sleep(1.0)
        rate_final = page.locator('.stat-card', has=page.locator('.stat-label', has_text='回答好评率')).locator('.stat-num').inner_text()
        report['steps'].append(f"praise_final={rate_final}")
    else:
        report['steps'].append('only_one_answer_skip_down')

    browser.close()

report['ok'] = True
print(json.dumps(report, ensure_ascii=False, indent=1))
