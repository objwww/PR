package com.objwww.pr.control.infrastructure.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * R7-X10 代码源绑定（R7 方案 §二 L64/L209：宿主将告警服务绑定到获准仓库的
 * 不可变 commit）：配置静态声明 {@code service=repo@commit} 逗号清单 + checkout
 * 根目录；模型只能按 service 取已绑定源——任意仓库名/绝对路径/越界路径在形状面
 * 与沙箱面双重拒绝（禁止模型指定未授权仓库）。
 *
 * <p>第一期=宿主声明绑定（provenance 如实标注 host-declared）；部署镜像 digest →
 * commit 的运行时核验属后续卡（偏差登记）。路径沙箱：相对路径、禁 {@code ..} 段、
 * 解析后必须落在 repo 目录内、只读常规文件。
 */
public final class CodeSourceBinding {

    /** 遍历跳过目录（依赖/构建产物/隐藏目录——代码取证不需要且量不可控） */
    static final Set<String> SKIP_DIRS =
            Set.of(".git", ".idea", "node_modules", "target", "build", "dist");

    /** service → {repo, commit} 绑定条目 */
    public record Entry(String repo, String commit) {
    }

    private CodeSourceBinding() {
    }

    /**
     * 解析映射清单（构造期 fail-fast，坏形状=启动失败，docker 同律）：
     * {@code "control-app=pr-agent@9c3f2e1,web=pr-web@a1b2c3d4"}。repo/commit 形状
     * 最小校验（非空、不含路径分隔符——commit 允许短/全 sha 与 tag 形状）。
     */
    public static Map<String, Entry> parse(String mapping) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (String item : mapping.split(",")) {
            String piece = item.strip();
            if (piece.isEmpty()) {
                continue;
            }
            int eq = piece.indexOf('=');
            int at = piece.indexOf('@');
            if (eq <= 0 || at <= eq + 1 || at == piece.length() - 1) {
                throw new IllegalArgumentException(
                        "代码源映射形状非法（service=repo@commit 逗号清单）: " + piece);
            }
            String service = piece.substring(0, eq).strip();
            String repo = piece.substring(eq + 1, at).strip();
            String commit = piece.substring(at + 1).strip();
            if (service.isEmpty() || repo.contains("/") || repo.contains("\\")
                    || repo.contains("..") || commit.contains("/")
                    || commit.contains("\\")) {
                throw new IllegalArgumentException(
                        "代码源映射形状非法（repo/commit 不得含路径分隔符或 ..）: " + piece);
            }
            out.put(service, new Entry(repo, commit));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("代码源映射为空（fail-closed）");
        }
        return out;
    }

    /**
     * 沙箱解析：相对路径（'/' 分隔）→ checkout 根下 repo 目录内的常规文件绝对路径。
     * 任何越界（绝对路径、反斜杠、{@code ..} 段、解析后逃出 repo、非常规文件）→ null
     * （调用面翻译 INVALID_ARGS）。
     */
    static Path resolveFile(Path checkoutRoot, Entry entry, String relativePath) {
        if (relativePath == null || relativePath.isBlank()
                || relativePath.startsWith("/") || relativePath.contains("\\")) {
            return null;
        }
        Path repoRoot = checkoutRoot.resolve(entry.repo()).normalize();
        Path current = repoRoot;
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return null;
            }
            current = current.resolve(segment);
        }
        current = current.normalize();
        if (!current.startsWith(repoRoot)) {
            return null;
        }
        try {
            return Files.isRegularFile(current) ? current : null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** repo 目录存在性（缺席=SOURCE_UNAVAILABLE 语义面） */
    public static boolean repoDirExists(Path checkoutRoot, Entry entry) {
        return Files.isDirectory(checkoutRoot.resolve(entry.repo()));
    }
}
