package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.port.GitHubSourcePort;

import java.util.HashMap;
import java.util.Map;

/**
 * T0 的 GitHub 只读源桩：按 headSha 注册 tarball、按 (baseSha, headSha) 注册 diff 文本。
 * 评审链路的真实性在 PG/状态机/outbox/publisher 侧；源内容本身是字节，用桩注入即可。
 */
final class FakeGitHubSourcePort implements GitHubSourcePort {

    private final Map<String, byte[]> tarballsBySha = new HashMap<>();
    private final Map<String, String> diffsByRange = new HashMap<>();

    FakeGitHubSourcePort registerSnapshot(String headSha, byte[] tarGz) {
        tarballsBySha.put(headSha, tarGz);
        return this;
    }

    FakeGitHubSourcePort registerDiff(String baseSha, String headSha, String diffText) {
        diffsByRange.put(baseSha + ".." + headSha, diffText);
        return this;
    }

    @Override
    public byte[] fetchTarball(long installationId, String repoFullName, String sha) {
        byte[] tarball = tarballsBySha.get(sha);
        if (tarball == null) {
            throw new IllegalStateException("桩未注册 tarball: " + sha);
        }
        return tarball;
    }

    @Override
    public String fetchDiff(long installationId, String repoFullName, String baseSha, String headSha) {
        String diff = diffsByRange.get(baseSha + ".." + headSha);
        if (diff == null) {
            throw new IllegalStateException("桩未注册 diff: " + baseSha + ".." + headSha);
        }
        return diff;
    }
}
