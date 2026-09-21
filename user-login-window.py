# -*- coding: utf-8 -*-
# 弹出本地 Edge 窗口：打开登录页并自动填好测试账号密码（不自动提交，留给用户点登录）
import time
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:18090'

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=False)
    page = browser.new_page(viewport={'width': 1440, 'height': 900})
    page.goto(BASE + '/login', wait_until='networkidle')
    page.fill('input[type=text]', 'operator')
    page.fill('input[type=password]', 'Demo#0917')
    # 等待登录跳转（用户手动点击登录）；最长挂 2 小时供演示
    deadline = time.time() + 7200
    while time.time() < deadline:
        time.sleep(2)
        if '/login' not in page.url:
            break
    time.sleep(600)
    browser.close()
