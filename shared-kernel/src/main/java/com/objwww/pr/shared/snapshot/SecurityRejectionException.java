package com.objwww.pr.shared.snapshot;

/**
 * 安全解包拒绝（UT-09/EX-10）：恶意或超限的 tar 条目一律快速失败，带原因码落安全事件。
 * 安全步骤不降级——抛出即终止本次快照准备，不做"跳过坏条目继续"的宽纵。
 */
public final class SecurityRejectionException extends RuntimeException {

    /** 拒绝原因码（落账/告警用，稳定枚举值勿改名） */
    public enum Reason {
        /** 绝对路径条目（"/etc/passwd"、"C:\..."） */
        ABSOLUTE_PATH,
        /** 含 ".." 段的路径穿越 */
        PATH_TRAVERSAL,
        /** symlink/hardlink 目标解析后逃出解包根 */
        ESCAPED_LINK,
        /** 设备文件 / FIFO 等特殊条目 */
        SPECIAL_FILE,
        /** 单文件解压后大小超限 */
        FILE_TOO_LARGE,
        /** 文件数超限 */
        TOO_MANY_FILES,
        /** 总解压大小超限（压缩炸弹防线） */
        TOTAL_SIZE_EXCEEDED
    }

    private final Reason reason;
    private final String entryName;

    public SecurityRejectionException(Reason reason, String entryName, String detail) {
        super("安全解包拒绝 [" + reason + "] entry=" + entryName + " : " + detail);
        this.reason = reason;
        this.entryName = entryName;
    }

    public Reason reason() {
        return reason;
    }

    public String entryName() {
        return entryName;
    }
}
