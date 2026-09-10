# UI-UX01 前端接线取证（2026-09-11）

对象：告警分类（UX-01）前端——alert-web 列表分类徽章列/分类过滤、详情分类区块 + 人工修正/撤销对话框。
195 已部署后端为**旧契约**（行无 `category`/`categorySource`、详情无 `categoryDetail`、facets 无 category 维），
取证目标 = 降级不报错、不伪造分类、修正提交显式提示"依赖后端 UX-01，当前未部署"。

## 复跑

```
ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225   # 前置隧道
cd docs/测试证据/UI-UX01前端-20260911
node ux01-screenshot-cdp.mjs
```

Edge headless + CDP，窗口 1366×768，真登录 test/12345678。

## 旧契约探针（脚本控制台输出）

`{"rowCategoryKeysPresent":[],"detailCategoryKeysPresent":[],"facetCategoryPresent":false}` —— 三处新键全部缺席。

## 截图清单

| 文件 | 内容 |
|---|---|
| s01-alerts-list-old-backend-degraded.png | 告警列表：分类列全部降级 "—"；分类下拉禁用 + 旁注"分类过滤依赖后端 UX-01（未部署）"；facet 栏只有 状态/严重度/服务（无分类组） |
| s02-incident-detail-category-absent.png | 详情概览：分类区块照常渲染——生效分类 "—"、命中规则 ID/规则版本/分类时间 均"未统计"、无人工修正快照；"人工修正"按钮在场 |
| s03-override-dialog-open.png | 人工修正对话框打开态：目标分类下拉（静态词表 8 项：业务/应用/依赖/基础设施/网络/数据/安全/平台，控制台探针记录）+ 理由必填，未填时提交禁用 |
| s04-override-submit-not-deployed-tip.png | 提交后错误提示态：POST /api/v1/incidents/{id}/category-override 旧后端 404 → ElMessageBox 显式"修正接口依赖后端 UX-01，当前未部署"，不静默不假装成功 |

补充探针：分类下拉 `.el-select__wrapper` 带 `is-disabled`（Element Plus 2.14 禁用类挂在内层 wrapper，根节点无该类）。
