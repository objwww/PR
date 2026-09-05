# P-02 必须 read_only：容器根文件系统只读，写路径收敛到显式声明的 tmpfs/卷，
# 压低篡改与落盘面。未显式声明 read_only: true（缺省或 false）即违规。
package main

import rego.v1

deny contains msg if {
	some name
	svc := input.services[name]
	not svc.read_only
	msg := sprintf("P-02 read-only-rootfs: service %q must set read_only: true (writable paths only via declared tmpfs/volumes)", [name])
}
