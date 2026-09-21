#!/bin/sh
echo '--- checkout rpc_client_call_duration_seconds_count 全系列（error_type 面）---'
curl -sG 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=rpc_client_call_duration_seconds_count{service="checkout"}' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
for r in d['data']['result']:
    m = r['metric']
    keep = {k: v for k, v in m.items() if k not in ('job', 'instance', 'container_id', 'docker_cli_cobra_command_path', 'exported_job', 'host_arch', 'host_cpu_cache_l2_size', 'host_cpu_family', 'host_cpu_model_id', 'host_cpu_model_name', 'host_cpu_stepping', 'host_cpu_vendor_id', 'host_name', 'os_description', 'os_type', 'process_command', 'process_command_args', 'process_executable_path', 'process_pid', 'process_runtime_description', 'process_runtime_version', 'service_criticality', 'telemetry_sdk_language', 'telemetry_sdk_name', 'telemetry_sdk_version', 'service_name')}
    print(m.get('__name__'), r['value'][1], keep)
"
echo '--- 5m 增速（rate）---'
curl -sG 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=sum by (error_type, rpc_grpc_status_code)(rate(rpc_client_call_duration_seconds_count{service="checkout"}[5m]))' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
for r in d['data']['result']:
    print(r['metric'], '->', r['value'][1])
"
