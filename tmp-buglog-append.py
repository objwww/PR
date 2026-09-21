import io

PATH = r"E:/kimiCode/docs/告警-BUGLOG.md"
with io.open(PATH, encoding="utf-8") as f:
    text = f.read()

quote = (
    "> 2026-09-14 RV 复查修复批（方案[告警-优化后详细审查与修改测试方案-20260914.md]"
    "(告警-优化后详细审查与修改测试方案-20260914.md)，台账[卡级状态 manifest]"
    "(告警-卡级状态manifest-20260914.json)）：RV01~RV08+RV11 全实现并 195 部署生效验证；"
    "RV04 补结构化回执生产能力（ChildResult 四清单）+同短事务收尾+ON CONFLICT 幂等+引用 Host 校验"
    "（T17/T18/T22+真 PG IT T20/T21）；RV03 在飞生命周期重写（T12~T15）；RV11 前端拆包首包 gzip "
    "618→141kB；RV09 出口验收脚本 v2（FRAMEWORK-READY，注入批次归部署窗）；RV10/BA-141 审计面"
    "登记 NOT_DONE 不猜根因；BA-142 结构性缺口收口。BA-145 本窗立案并关单（overlay 删除不传播）。\n\n"
)

anchor = "> 规则：遇到 Bug 当场记录；"
assert anchor in text, "header anchor missing"
text = text.replace(anchor, quote + anchor, 1)

row = (
    "\n| BA-145 | 已修复关单（当窗确定性重建后镜像换代证明+live jar 七指纹全过） "
    "| **RV 批 195 部署首窗全绿假象**：mvn 两段全在 shared-kernel 失败"
    "（org.apache.commons.compress 包不存在）；compose build 因 COPY 层未变复用旧镜像，"
    "up -d 后 live jar=旧构建（Started+health 200 不区分新旧代码）；首版指纹脚本又从残留解包目录"
    "读到 ChildResult 假阳性，两假叠加险些误判部署生效 "
    "| 清理批 b987d279 删 R-C05 snapshot 三件套（git 工作树删除），而 195 同步=sr-mktar tarball"
    "+OVERLAY 解包（BA-137 纪律，永不 --delete）→git 删除不传播，孤儿 SafeTarExtractor.java 等 "
    "4 件留 195；当次 overlay 刷新存量源 mtime→maven-compiler 增量编译判定失效→全量重编译撞上"
    "孤儿源（commons-compress 依赖已随清理批离场）；control-app 侧另有 ModelRouteCatalog/"
    "MockModelGateway 两孤儿（编译没走到故未爆） "
    "| overlay 同步机制对「删除」零传播能力+部署验收缺「镜像换代证明」段（compose build 成功日志≠"
    "新镜像） "
    "| 六孤儿显式镜像删除（git 真值 diff 法：git ls-files×服务器 find 全模块 src+pom 比对，"
    "scope 限定 maven 源树，绝不触碰 deploy/.env）；mvn clean package 双模块 BUILD SUCCESS；"
    "docker inspect .Id/.Created 前后对拍证明换代（534f9d7f/893a945b，2026-09-14 02:54+08）；"
    "唯一有效验收=live 容器 docker cp jar+javap 类常量指纹七面全中；伴生修正：javap 目标类名三连错"
    "（枚举常量在 \\$Handle\\$Phase；嵌套 record 在 RoleRunner\\$RoleDriveResult\\$ChildResult；"
    "private 方法需 -p） "
    "| 每次 overlay 部署前置孤儿 diff；镜像换代证明（.Created 变化）入部署脚本固定段；"
    "live jar 指纹为唯一部署生效判据，解包目录当次 rm -rf 重建不复用 "
    "| BA-137 同族；b987d279 清理批；部署证据 /tmp/rv-rebuild.log+/tmp/rv-fingerprint2.log 口径见 "
    "PROGRESS 2026-09-14 行 |\n"
)
if "BA-145" not in text:
    if not text.endswith("\n"):
        text += "\n"
    text += row

with io.open(PATH, "w", encoding="utf-8", newline="") as f:
    f.write(text)
print("BA-145 appended, quote inserted:", "BA-145" in text)
