# P-03 必须 cap_drop ALL：先弃尽 Linux capabilities，再按需加回，
# 缺省能力集对越权/逃逸链路是现成的放大器。cap_drop 缺失或不含 ALL 即违规。
package main

import rego.v1

deny contains msg if {
	some name
	svc := input.services[name]
	caps := object.get(svc, "cap_drop", [])
	not all_dropped(caps)
	msg := sprintf("P-03 cap-drop-all: service %q must set cap_drop: [ALL] (add back only proven-needed caps)", [name])
}

all_dropped(caps) if {
	"ALL" in caps
}
