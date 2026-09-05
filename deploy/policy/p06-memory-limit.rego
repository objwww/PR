# P-06 必须内存限额：每个服务都要有硬内存上限（mem_limit 或等价的
# deploy.resources.limits.memory，渲染物中两者同现），防单容器拖垮宿主。
# 0/缺失/空均视为未设限。
package main

import rego.v1

memory_limited(svc) if svc.mem_limit > 0

memory_limited(svc) if svc.deploy.resources.limits.memory > 0

deny contains msg if {
	some name
	svc := input.services[name]
	not memory_limited(svc)
	msg := sprintf("P-06 memory-limit: service %q must set mem_limit (or deploy.resources.limits.memory) > 0", [name])
}
