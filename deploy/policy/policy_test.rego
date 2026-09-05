# 正反样本单元测试：conftest verify -p deploy/policy/
# hardened = 逐条满足 P-01~P-07 的服务（正样本基线）；每个测试在其上做单点变异，
# 断言对应策略恰好触发/不触发，防止策略间相互遮蔽。
package main

import rego.v1

hardened := {
	"image": "pr-agent/order-arena:0.0.1-SNAPSHOT",
	"read_only": true,
	"cap_drop": ["ALL"],
	"user": "10001:10001",
	"mem_limit": 536870912,
	"security_opt": ["no-new-privileges:true"],
	"ports": [{"mode": "host", "host_ip": "127.0.0.1", "target": 8080, "published": "8081", "protocol": "tcp"}],
}

denymsgs(doc) := msgs if {
	msgs := deny with input as {"services": {"svc-under-test": doc}}
}

pfx_msgs(doc, prefix) := [m | some m in denymsgs(doc); startswith(m, prefix)]

# ---- 正样本：完全加固的服务全绿；不带 ports 的服务 P-04 自然通过 ----

test_hardened_service_has_zero_violations if {
	count(denymsgs(hardened)) == 0
}

test_service_without_ports_passes_p04 if {
	doc := object.union(hardened, {"ports": []})
	count(pfx_msgs(doc, "P-04")) == 0
	count(denymsgs(doc)) == 0
}

# ---- P-00 输入体检（warn，不计入 deny）----

test_p00_warns_on_non_compose_input if {
	ws := warn with input as {"not-compose": true}
	count(ws) == 1
}

test_p00_silent_on_compose_render if {
	ws := warn with input as {"services": hardened}
	count(ws) == 0
}

# ---- P-01 禁 privileged ----

test_p01_denies_privileged_true if {
	count(pfx_msgs(object.union(hardened, {"privileged": true}), "P-01")) == 1
}

test_p01_allows_privileged_false if {
	count(pfx_msgs(object.union(hardened, {"privileged": false}), "P-01")) == 0
}

# ---- P-02 必须 read_only ----

test_p02_denies_readonly_false if {
	count(pfx_msgs(object.union(hardened, {"read_only": false}), "P-02")) == 1
}

test_p02_denies_readonly_absent if {
	count(pfx_msgs(object.remove(hardened, ["read_only"]), "P-02")) == 1
}

# ---- P-03 必须 cap_drop ALL ----

test_p03_denies_capdrop_absent if {
	count(pfx_msgs(object.remove(hardened, ["cap_drop"]), "P-03")) == 1
}

test_p03_denies_partial_capdrop if {
	count(pfx_msgs(object.union(hardened, {"cap_drop": ["NET_BIND_SERVICE"]}), "P-03")) == 1
}

# ---- P-04 禁公网端口绑定 ----

test_p04_denies_wildcard_host_ip if {
	doc := object.union(hardened, {"ports": [{"host_ip": "0.0.0.0", "target": 8080, "published": "8081"}]})
	count(pfx_msgs(doc, "P-04")) == 1
}

test_p04_denies_unset_host_ip if {
	doc := object.union(hardened, {"ports": [{"target": 8080, "published": "8081"}]})
	count(pfx_msgs(doc, "P-04")) == 1
}

test_p04_denies_lan_host_ip if {
	doc := object.union(hardened, {"ports": [{"host_ip": "192.168.1.10", "target": 8080, "published": "8081"}]})
	count(pfx_msgs(doc, "P-04")) == 1
}

test_p04_denies_short_syntax_wildcard if {
	doc := object.union(hardened, {"ports": ["8081:8080"]})
	count(pfx_msgs(doc, "P-04")) == 1
}

test_p04_allows_loopback_short_syntax if {
	doc := object.union(hardened, {"ports": ["127.0.0.1:8081:8080"]})
	count(pfx_msgs(doc, "P-04")) == 0
}

test_p04_denies_network_mode_host if {
	doc := object.union(hardened, {"network_mode": "host"})
	count(pfx_msgs(doc, "P-04")) == 1
}

# ---- P-05 必须 non-root ----

test_p05_denies_user_absent if {
	count(pfx_msgs(object.remove(hardened, ["user"]), "P-05")) == 1
}

test_p05_denies_root_uid if {
	count(pfx_msgs(object.union(hardened, {"user": "0:0"}), "P-05")) == 1
}

test_p05_denies_root_name if {
	count(pfx_msgs(object.union(hardened, {"user": "root"}), "P-05")) == 1
}

# ---- P-06 必须内存限额 ----

test_p06_denies_memlimit_absent if {
	count(pfx_msgs(object.remove(hardened, ["mem_limit"]), "P-06")) == 1
}

test_p06_denies_zero_memlimit if {
	count(pfx_msgs(object.union(hardened, {"mem_limit": 0}), "P-06")) == 1
}

test_p06_allows_deploy_resources_memory if {
	doc := object.union(object.remove(hardened, ["mem_limit"]), {"deploy": {"resources": {"limits": {"memory": 536870912}}}})
	count(pfx_msgs(doc, "P-06")) == 0
}

# ---- P-07 必须 no-new-privileges ----

test_p07_denies_security_opt_absent if {
	count(pfx_msgs(object.remove(hardened, ["security_opt"]), "P-07")) == 1
}

test_p07_denies_unrelated_security_opt if {
	count(pfx_msgs(object.union(hardened, {"security_opt": ["seccomp:unconfined"]}), "P-07")) == 1
}

test_p07_allows_bare_nnp if {
	count(pfx_msgs(object.union(hardened, {"security_opt": ["no-new-privileges"]}), "P-07")) == 0
}
