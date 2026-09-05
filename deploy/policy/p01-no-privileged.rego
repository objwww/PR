# P-01 禁 privileged：privileged=true 把宿主全部设备与内核能力交给容器，
# 等价于放弃容器隔离，属静态门一票否决项（无豁免路径）。
package main

import rego.v1

deny contains msg if {
	some name
	input.services[name].privileged == true
	msg := sprintf("P-01 no-privileged: service %q sets privileged=true (full host device/kernel access is forbidden)", [name])
}
