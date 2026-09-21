# -*- coding: utf-8 -*-
import io

p = r'control-app/src/main/java/com/objwww/pr/control/alert/application/tool/ToolGateway.java'
t = io.open(p, encoding='utf-8').read()

old = """        Future<byte[]> future;
        try {
            future = callPool.submit(() -> registration.executor().execute(execution));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // EX-A4a（F17）：bulkhead 满则明确拒绝——独立池有界队列的背压语义，
            // 不静默排队也不靠兜底映射；模型可见族（可退避重试）
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具调用通道拥塞（背压拒绝，可稍后重试）");
        }
        if (cancels != null) {
            cancels.register(invocation.runId(), future);
        }
        try {"""
new = """        // RV03：先注册句柄（QUEUED）再入池——执行包装器入口做 QUEUED→RUNNING CAS
        // （取消与启动竞争，取消胜=零执行），finally 置实际退出事实
        InFlightToolCancels.Handle handle = cancels == null
                ? null : cancels.register(invocation.runId());
        Future<byte[]> future;
        try {
            future = callPool.submit(() -> {
                if (handle != null && !handle.beginRun()) {
                    // 排队取消获胜：执行体不启动（执行次数 0），类型化停止
                    throw new com.objwww.pr.control.alert.application.ExecutionControl
                            .StoppedException(
                            com.objwww.pr.control.alert.application.ExecutionControl
                                    .STOP_RUN_CANCELLED,
                            "run " + invocation.runId() + " 已取消，排队取消不启动执行");
                }
                try {
                    return registration.executor().execute(execution);
                } finally {
                    if (handle != null) {
                        cancels.exit(handle); // 执行体 finally 才是退出事实（RV03/T12）
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // EX-A4a（F17）：bulkhead 满则明确拒绝——独立池有界队列的背压语义，
            // 不静默排队也不靠兜底映射；模型可见族（可退避重试）。入池拒绝走回收路径
            if (handle != null) {
                cancels.reclaim(invocation.runId(), handle);
            }
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具调用通道拥塞（背压拒绝，可稍后重试）");
        }
        if (handle != null) {
            handle.attach(future); // 中断面（cancel(true) 只请求中断，不证明退出）
        }
        try {"""
assert old in t
t = t.replace(old, new)

old = """        } finally {
            if (cancels != null) {
                cancels.unregister(invocation.runId(), future);
            }
        }
    }"""
new = """        } finally {
            if (handle != null) {
                // RV03：调用方 finally 只结束「等待」——执行体忽略中断仍在运行时，
                // 在飞事实由句柄保留（EXITED 由执行包装器 finally 回收）
                cancels.endWait(invocation.runId(), handle);
            }
        }
    }"""
assert old in t
t = t.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(t)
print('gateway ok')
