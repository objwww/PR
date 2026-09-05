# P-00 输入体检（warn，不参与门禁退出码）：
# conftest 对"零违规"与"策略没跑上"输出看起来一样，若误把非 compose 渲染物
# 灌进门禁会得到假绿。此规则只在 input 顶层没有 services 时提示，帮助区分两者。
package main

import rego.v1

warn contains msg if {
	not input.services
	msg := "P-00 sanity: input has no top-level \"services\" object - is this really a `docker compose config` render?"
}
