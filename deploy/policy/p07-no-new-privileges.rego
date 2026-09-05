# P-07 必须 no-new-privileges：security_opt 含 no-new-privileges，封死 setuid/setgid
# 提权路径（含 read_only+nonroot 组合仍可能借文件位提权的残余面）。
# 兼容 "no-new-privileges" 与 "no-new-privileges:true" 两种写法。
package main

import rego.v1

nnp_values := {"no-new-privileges", "no-new-privileges:true"}

deny contains msg if {
	some name
	svc := input.services[name]
	not has_nnp(object.get(svc, "security_opt", []))
	msg := sprintf("P-07 no-new-privileges: service %q must set security_opt: [no-new-privileges:true]", [name])
}

has_nnp(opts) if {
	some opt in opts
	opt in nnp_values
}
