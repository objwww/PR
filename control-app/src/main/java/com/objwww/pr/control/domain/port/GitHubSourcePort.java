package com.objwww.pr.control.domain.port;

/**
 * GitHub 只读源端口（domain 端口）：按不可变 SHA 取快照与 diff，只 archive 不 checkout（B-6）。
 * 实现（infrastructure GitHubReadAdapter）内部经 CredentialTokenPort 申请只读 token，
 * 接口签名上不带 token——凭证不出现在 domain 语义里。
 */
public interface GitHubSourcePort {

    /** 取 {@code repoFullName} 在 {@code sha} 处的 tarball（gzip 压缩字节流） */
    byte[] fetchTarball(long installationId, String repoFullName, String sha);

    /** 取 base..head 的 unified diff 文本 */
    String fetchDiff(long installationId, String repoFullName, String baseSha, String headSha);
}
