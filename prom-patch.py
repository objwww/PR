#!/usr/bin/env python3
# prometheus.yml 补丁：service_name → service 标签映射（幂等）
import sys

p = '/opt/build/pr/deploy/alert/prometheus/prometheus.yml'
s = open(p).read()
if 'metric_relabel_configs' in s:
    print('already-patched')
    sys.exit(0)
old = """  - job_name: otel-collector
    static_configs:
      - targets: ['otel-collector:9464']"""
new = """  - job_name: otel-collector
    static_configs:
      - targets: ['otel-collector:9464']
    metric_relabel_configs:
      # EN-01/MC34 真窗修复（2026-09-12）：collector prometheus exporter 以
      # service_name 透出 service.name 资源属性，工具面（allowlist 选择器/告警
      # 材料/模型查询）契约按 service 标签——映射恢复标签兼容，双标签共存加法不改旧
      - source_labels: [service_name]
        target_label: service
        regex: '(.+)'
        replacement: '$1'
        action: replace"""
assert old in s, 'anchor-not-found'
open(p, 'w').write(s.replace(old, new))
print('patched')
