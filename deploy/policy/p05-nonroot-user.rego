# P-05 必须 non-root：compose 层显式钉死非 root 运行身份（user: "UID[:GID]"）。
# 局限（README 已注明）：镜像内置 USER 无法从渲染物静态验证——compose 不设 user
# 时容器以镜像默认身份（可能为 root）运行，故未显式钉 user 即按违规处理。
package main

import rego.v1

root_identities := {"0", "0:0", "0:root", "root", "root:0", "root:root"}

deny contains msg if {
	some name
	svc := input.services[name]
	object.get(svc, "user", "") == ""
	msg := sprintf("P-05 non-root: service %q must pin a non-root user (user: \"UID[:GID]\"); image-level USER cannot be verified statically", [name])
}

deny contains msg if {
	some name
	svc := input.services[name]
	user := object.get(svc, "user", "")
	user in root_identities
	msg := sprintf("P-05 non-root: service %q pins the root identity (user=%q)", [name, user])
}
