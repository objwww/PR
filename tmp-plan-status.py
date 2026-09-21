import io

PATH = r"E:/kimiCode/docs/告警-优化后详细审查与修改测试方案-20260914.md"
with io.open(PATH, encoding="utf-8") as f:
    text = f.read()

status = (
    "\n> **执行状态（2026-09-14 部署+服务器测试窗回填）**：A 批（RV01/05/06/07/08+定位准入守卫）"
    "→ commit RV-A+C03；B 批（RV02 四层分离）同批；C 批（RV03 重写+RV04 回执生产能力/同短事务/"
    "幂等/Host 校验）→ RV-C04；E 批（RV11 拆包）同批。全量回归 3990/0/0。195 部署=2026-09-14 "
    "02:54 确定性重建（BA-145 孤儿清理后），live jar 指纹验收全中；服务器测试窗（全量+真 PG IT "
    "T19~T21）同窗执行见 PROGRESS。D 批（RV12 manifest）与 RV09 脚本 v2 已入库（FRAMEWORK-READY，"
    "T34~T37 注入批次归后续部署窗）；RV10/BA-141 审计面 NOT_DONE 登记，T38/T39 归后续窗口；"
    "T04/T31~T33 浏览器走查、T24/T26/T30 PG 面归后续接入窗。五维状态终值以"
    "[卡级状态 manifest](告警-卡级状态manifest-20260914.json) 为准。\n"
)

anchor = "**一、实际做了哪些验证**"
assert anchor in text, "anchor missing"
text = text.replace(anchor, status + "\n" + anchor, 1)

with io.open(PATH, "w", encoding="utf-8", newline="") as f:
    f.write(text)
print("plan status inserted")
