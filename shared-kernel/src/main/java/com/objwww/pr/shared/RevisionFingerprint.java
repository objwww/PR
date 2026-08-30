package com.objwww.pr.shared;

/**
 * 不可变代码身份指纹（pr_revision.revision_fingerprint，char(64)）。
 * 只含代码身份要素，不含 policy/prompt/toolset（v2.2 §3 revision 与治理版本拆分）。
 */
public record RevisionFingerprint(String value) {

    public RevisionFingerprint {
        // 复用 Digest 的 64 位 hex 校验
        value = new Digest(value).value();
    }

    @Override
    public String toString() {
        return value;
    }
}
