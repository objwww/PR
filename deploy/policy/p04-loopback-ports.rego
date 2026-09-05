# P-04 禁公网端口绑定：宿主端口只许绑 127.0.0.1（INV-AM0-1，取证/管理走 SSH 隧道）。
# 长语法看 host_ip 字段；短语法按首个冒号段判定；host_ip 缺省 = 绑 0.0.0.0，即违规。
# 服务不带 ports 时自然通过（容器网内互通不经宿主端口）。仅放行 IPv4 的 127.0.0.1。
package main

import rego.v1

deny contains msg if {
	some name
	some port in object.get(input.services[name], "ports", [])
	is_object(port)
	object.get(port, "host_ip", "") != "127.0.0.1"
	msg := sprintf("P-04 loopback-ports: service %q publishes %v:%v with host_ip %q (must bind 127.0.0.1; unset means 0.0.0.0)", [
		name,
		object.get(port, "published", "?"),
		object.get(port, "target", "?"),
		object.get(port, "host_ip", "<unset>"),
	])
}

deny contains msg if {
	some name
	some port in object.get(input.services[name], "ports", [])
	is_string(port)
	not loopback_binding(port)
	msg := sprintf("P-04 loopback-ports: service %q port %q is not bound to 127.0.0.1", [name, port])
}

loopback_binding(port) if {
	parts := split(port, ":")
	count(parts) >= 2
	parts[0] == "127.0.0.1"
}

# network_mode: host 直接共享宿主网络栈，完全绕过 ports 发布——比绑 0.0.0.0 更宽，
# 同属 P-04 的禁止面。
deny contains msg if {
	some name
	input.services[name].network_mode == "host"
	msg := sprintf("P-04 loopback-ports: service %q uses network_mode host (shares host netns, bypasses port publishing)", [name])
}
