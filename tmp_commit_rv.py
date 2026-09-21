# -*- coding: utf-8 -*-
import subprocess

files = [
    r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/PrimaryClaimAdmission.java',
    r'control-app/src/main/java/com/objwww/pr/control/alert/application/tool/InFlightToolCancels.java',
    r'control-app/src/main/java/com/objwww/pr/control/alert/application/tool/ToolGateway.java',
    r'control-app/src/test/java/com/objwww/pr/control/alert/application/agent/PrimaryClaimAdmissionTest.java',
    r'control-app/src/test/java/com/objwww/pr/control/alert/application/tool/InFlightToolCancelsLifecycleTest.java',
    r'control-app/src/test/java/com/objwww/pr/control/alert/application/RunQueryServiceTest.java',
    r'notify-app/src/main/java/com/objwww/pr/notify/domain/service/FencedNotifyExecutor.java',
    r'notify-app/src/test/java/com/objwww/pr/notify/domain/service/FencedNotifyExecutorTest.java',
    r'duty-adapter/src/main/java/com/objwww/pr/duty/webhook/DutyWebhookClient.java',
    r'alert-web/src/views/DutyChatView.vue',
    r'alert-web/src/views/NotificationsView.vue',
]
r = subprocess.run(['git', 'add', '--'] + files, capture_output=True, text=True,
                   encoding='utf-8', errors='replace')
print('add rc', r.returncode, r.stderr[-300:] if r.returncode else '')

msg = (
"fix(rv-a+c03): 审查方案 A 批+RV03 取消生命周期（docs/告警-优化后详细审查与修改测试方案-20260914.md）——"
"RV01 通知预览 HTML 注入封口（escapeHtml 引号全转义=插值恒来自已转义文本的结构性不变量+URL 白名单收紧+font 正则同步转义形，T01~T03）；"
"RV05 通知错误落账 Jackson 化（固定 reason 码+detail 限长 1000，引号/换行/TAB 合法化，retry reason/detail 分立；"
"DutyWebhookClient quote 控制字符全转义）；RV06 null 渠道契约面 channel_not_configured→DEAD 零触网（不再 NPE）；"
"RV07 期限闸前置到 execute 入口（超龄首发零触网；等于边界可发）+sent_at=发送确认后取值；"
"RV08 通知页单调请求序号（切筛选必发新请求、旧响应写回前校验序号、loading 锁只防同筛选加载更多）；"
"RV03 在飞事实生命周期（句柄状态机 QUEUED/RUNNING/STOP_REQUESTED/CANCELLED_BEFORE_START/EXITED："
"执行包装器 finally 才是退出事实、调用方 finally 只结束等待、排队取消 CAS 零执行、"
"注册/删除按 Run 原子 compute、入池拒绝走回收、墓碑显式 release 不做 TTL、interrupt 吞已完成 future 的 CancellationException）；"
"RV02 前置：locator 数值/形状护栏（超 int 下标/过深路径判定位失败不抛 NumberFormatException，错误仅影响当前引用）+"
"RV02 证据准入边界（UUID 引用读不回/跨 Run→CONTEXT 留痕、非 UUID artifact 只有上下文资格、"
"载荷不可解析降 CONTEXT、SUPPORTS/REFUTES 对称受全量计数/累计计数器确定性检查、定位失败连支持关系一并降级）；"
"新测 InFlightToolCancelsLifecycleTest 5/5+PrimaryClaimAdmissionTest 13/13+FencedNotifyExecutorTest 12/12+"
"RunQueryServiceTest 9/9+ToolGatewayTest 14/14；全量回归 3974 例 0 失败（A+B 时点）。"
"遗留：RV04 回执生产能力/同事务收尾/ON CONFLICT、RV09 出口验收、RV11 拆包、RV12 manifest、195 服务器测试"
)
r = subprocess.run(['git', 'commit', '-m', msg], capture_output=True, text=True,
                   encoding='utf-8', errors='replace')
print('commit rc', r.returncode)
print((r.stdout or r.stderr)[-400:])
