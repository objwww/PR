package com.objwww.pr.control.infrastructure.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7-X10 代码源绑定 L0 面：映射解析 fail-fast（启动闸）+ 路径沙箱（模型侧任何
 * 越界形态零触达——禁绝对路径/反斜杠/../逃逸/非常规文件）。禁止模型指定未授权
 * 仓库是本卡红线，沙箱即其执行面。
 */
class CodeSourceBindingTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("映射解析：service=repo@commit 清单 → 绑定集；坏形状启动即拒")
    void parseBindingList() {
        Map<String, CodeSourceBinding.Entry> binding =
                CodeSourceBinding.parse("control-app=pr-agent@9c3f2e1, web=pr-web@a1b2c3d4");
        assertThat(binding).containsOnlyKeys("control-app", "web");
        assertThat(binding.get("control-app").repo()).isEqualTo("pr-agent");
        assertThat(binding.get("control-app").commit()).isEqualTo("9c3f2e1");

        assertThatThrownBy(() -> CodeSourceBinding.parse("control-app=pr-agent"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodeSourceBinding.parse("control-app@9c3f2e1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodeSourceBinding.parse("control-app=@9c3f2e1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodeSourceBinding.parse("control-app=../escape@c1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodeSourceBinding.parse("control-app=a/b@c1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodeSourceBinding.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("沙箱：源树内相对路径解析命中；.. 段/绝对路径/反斜杠/缺席文件全拒")
    void resolveWithinRepoOnly() throws Exception {
        Files.createDirectories(root.resolve("pr-agent/src"));
        Files.writeString(root.resolve("pr-agent/src/A.java"), "class A {}\n");
        CodeSourceBinding.Entry entry = new CodeSourceBinding.Entry("pr-agent", "c1");

        Path hit = CodeSourceBinding.resolveFile(root, entry, "src/A.java");
        assertThat(hit).isNotNull().isEqualTo(root.resolve("pr-agent/src/A.java"));

        assertThat(CodeSourceBinding.resolveFile(root, entry, "src/../secret.txt")).isNull();
        assertThat(CodeSourceBinding.resolveFile(root, entry, "/etc/passwd")).isNull();
        assertThat(CodeSourceBinding.resolveFile(root, entry, "src\\A.java")).isNull();
        assertThat(CodeSourceBinding.resolveFile(root, entry, "../other/x")).isNull();
        assertThat(CodeSourceBinding.resolveFile(root, entry, "src/Missing.java")).isNull();
        assertThat(CodeSourceBinding.resolveFile(root, entry, "src")).isNull();
    }

    @Test
    @DisplayName("repo 目录缺席检测（SOURCE_UNAVAILABLE 语义面）")
    void repoDirExists() {
        CodeSourceBinding.Entry entry = new CodeSourceBinding.Entry("no-such-repo", "c1");
        assertThat(CodeSourceBinding.repoDirExists(root, entry)).isFalse();
    }
}
