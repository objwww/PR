# P4 结构化输出稳定性——统计表

生成: analyze_p4.py 对 raw/*.jsonl 的机械汇总；判定链复刻 EvidencePackageValidator.parseAnalysis（整包→```json 围栏→围栏非法即拒→无围栏首'{'到末'}'片段）。

定义：**兜住率** = 「生产提取链成功取出 JSON 且七键 schema 完整」的响应占比；**schema 字段完整率**同口径（提取失败视作字段不完整，因为生产不会采纳该响应）。解析获救率 = 仅提取成功（不论 schema）。

| 模型 | 臂 | n | 纯JSON | 围栏包裹 | 散文 | 空/调用失败 | 提取路径 direct/fence/braces/拒 | schema字段完整 | 解析获救 | artifact_ref违规 | 平均延迟ms | 平均总tokens |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| deepseek-v3 | A 文字硬指令(BA-14修复) | 20 |   0.0% (0) | 100.0% (20) |   0.0% (0) | 0 | 0/20/0/0 | 100.0% (20) | 100.0% (20) | 0 | 7569 | 991 |
| deepseek-v3 | B response_format strict json_schema | 20 |   0.0% (0) | 100.0% (20) |   0.0% (0) | 0 | 0/20/0/0 |   0.0% (0) | 100.0% (20) | 0 | 13163 | 1127 |
| deepseek-v3 | C response_format json_object | 20 |   0.0% (0) | 100.0% (20) |   0.0% (0) | 0 | 0/20/0/0 |   0.0% (0) | 100.0% (20) | 0 | 15984 | 1217 |
| qwen-max | A 文字硬指令(BA-14修复) | 20 | 100.0% (20) |   0.0% (0) |   0.0% (0) | 0 | 20/0/0/0 | 100.0% (20) | 100.0% (20) | 0 | 11176 | 1153 |
| qwen-max | B response_format strict json_schema | 20 | 100.0% (20) |   0.0% (0) |   0.0% (0) | 0 | 20/0/0/0 |   0.0% (0) | 100.0% (20) | 0 | 17901 | 1267 |
| qwen-max | C response_format json_object | 20 | 100.0% (20) |   0.0% (0) |   0.0% (0) | 0 | 20/0/0/0 |   0.0% (0) | 100.0% (20) | 0 | 19728 | 1314 |
| qwen-plus | A 文字硬指令(BA-14修复) | 20 |  80.0% (16) |   0.0% (0) |  20.0% (4) | 0 | 16/0/4/4 |  80.0% (16) |  80.0% (16) | 0 | 16463 | 1507 |
| qwen-plus | B response_format strict json_schema | 20 | 100.0% (20) |   0.0% (0) |   0.0% (0) | 0 | 20/0/0/0 | 100.0% (20) | 100.0% (20) | 0 | 19814 | 1559 |
| qwen-plus | C response_format json_object | 20 | 100.0% (20) |   0.0% (0) |   0.0% (0) | 0 | 20/0/0/0 |   0.0% (0) | 100.0% (20) | 0 | 21583 | 1635 |

## schema 缺陷明细（提取成功但字段不完整的响应）

- `deepseek-v3/jsonobj/evidence[]` × 20
- `deepseek-v3/jsonobj/impact` × 20
- `deepseek-v3/jsonobj/references[]` × 20
- `deepseek-v3/jsonobj/remediation` × 20
- `deepseek-v3/jsonobj/root_cause` × 20
- `deepseek-v3/jsonobj/schema_version!=1` × 20
- `deepseek-v3/jsonobj/summary` × 20
- `deepseek-v3/schema/evidence[]` × 20
- `deepseek-v3/schema/impact` × 20
- `deepseek-v3/schema/ref.artifact_ref` × 24
- `deepseek-v3/schema/references[]` × 12
- `deepseek-v3/schema/remediation` × 20
- `deepseek-v3/schema/root_cause` × 20
- `deepseek-v3/schema/schema_version!=1` × 20
- `deepseek-v3/schema/summary` × 20
- `qwen-max/jsonobj/evidence[]` × 18
- `qwen-max/jsonobj/impact` × 20
- `qwen-max/jsonobj/ref.artifact_ref` × 7
- `qwen-max/jsonobj/references[]` × 16
- `qwen-max/jsonobj/remediation` × 20
- `qwen-max/jsonobj/root_cause` × 20
- `qwen-max/jsonobj/schema_version!=1` × 20
- `qwen-max/jsonobj/summary` × 20
- `qwen-max/schema/evidence[]` × 16
- `qwen-max/schema/impact` × 20
- `qwen-max/schema/references[]` × 13
- `qwen-max/schema/remediation` × 20
- `qwen-max/schema/root_cause` × 20
- `qwen-max/schema/schema_version!=1` × 20
- `qwen-max/schema/summary` × 20
- `qwen-plus/jsonobj/evidence[]` × 20
- `qwen-plus/jsonobj/impact` × 20
- `qwen-plus/jsonobj/ref.artifact_ref` × 7
- `qwen-plus/jsonobj/references[]` × 18
- `qwen-plus/jsonobj/remediation` × 20
- `qwen-plus/jsonobj/schema_version!=1` × 20
- `qwen-plus/jsonobj/summary` × 20
