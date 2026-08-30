package com.objwww.pr.publisher.infrastructure.selfcheck;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publisher 启动自检（B25/§6.5）：假探针覆盖每条拒绝路径与放行路径（无 docker 可跑）。
 * 冻结矩阵：publisher 角色对 outbox_command 只能 SELECT/UPDATE（不能 INSERT——不能伪造写意图）。
 */
class PublisherSelfCheckTest {

    private static final String KEY_PATH = "/run/secrets/github-app-key.pem";

    private record FakeFiles(boolean exists, Optional<Set<PosixFilePermission>> posix,
                             boolean writable) implements FileSecurityProbe {
        @Override
        public boolean exists(Path path) {
            return exists;
        }

        @Override
        public Optional<Set<PosixFilePermission>> posixPermissions(Path path) {
            return posix;
        }

        @Override
        public boolean writable(Path path) {
            return writable;
        }
    }

    private static final Set<PosixFilePermission> READ_ONLY = Set.of(PosixFilePermission.OWNER_READ);

    private static FakeFiles readOnlyKeyFile() {
        return new FakeFiles(true, Optional.of(READ_ONLY), false);
    }

    private static DbPrivilegeProbe goodDb() {
        return (table, privilege) -> switch (privilege) {
            case "INSERT" -> false;
            case "UPDATE" -> true;
            default -> throw new IllegalArgumentException(privilege);
        };
    }

    private static List<String> run(Map<String, String> env, DbPrivilegeProbe db,
                                    String userName, FileSecurityProbe files, String keyPath) {
        return PublisherSelfCheck.violations(env, db, () -> userName, files, keyPath);
    }

    @Test
    void passesWithHardenedEnvironment() {
        assertThat(run(Map.of(), goodDb(), "app", readOnlyKeyFile(), KEY_PATH)).isEmpty();
    }

    @Test
    void rejectsRootUser() {
        assertThat(run(Map.of(), goodDb(), "root", readOnlyKeyFile(), KEY_PATH))
                .anySatisfy(v -> assertThat(v).contains("root"));
    }

    @Test
    void rejectsUidZeroEvenWhenUserNameLooksFine() {
        assertThat(run(Map.of("UID", "0"), goodDb(), "app", readOnlyKeyFile(), KEY_PATH))
                .anySatisfy(v -> assertThat(v).contains("root"));
    }

    @Test
    void rejectsModelKeyPresence() {
        assertThat(run(Map.of("AGENT_MODEL_API_KEY", "sk"), goodDb(), "app",
                        readOnlyKeyFile(), KEY_PATH))
                .anySatisfy(v -> assertThat(v).contains("AGENT_MODEL_API_KEY"));
    }

    @Test
    void rejectsMissingKeyFile() {
        FileSecurityProbe missing = new FakeFiles(false, Optional.empty(), false);
        assertThat(run(Map.of(), goodDb(), "app", missing, KEY_PATH))
                .anySatisfy(v -> assertThat(v).contains("不存在"));
    }

    @Test
    void rejectsUnconfiguredKeyPath() {
        assertThat(run(Map.of(), goodDb(), "app", readOnlyKeyFile(), " "))
                .anySatisfy(v -> assertThat(v).contains("private-key-path"));
    }

    @Test
    void rejectsWritableKeyFileAnyWriteBit() {
        for (PosixFilePermission writeBit : List.of(PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)) {
            Set<PosixFilePermission> perms = java.util.EnumSet.of(PosixFilePermission.OWNER_READ, writeBit);
            FileSecurityProbe writable = new FakeFiles(true, Optional.of(perms), true);
            assertThat(run(Map.of(), goodDb(), "app", writable, KEY_PATH))
                    .as("含 %s 写位应拒绝", writeBit)
                    .anySatisfy(v -> assertThat(v).contains("只读"));
        }
    }

    @Test
    void nonPosixPlatformFallsBackToWritableCheck() {
        FileSecurityProbe writable = new FakeFiles(true, Optional.empty(), true);
        assertThat(run(Map.of(), goodDb(), "app", writable, KEY_PATH))
                .anySatisfy(v -> assertThat(v).contains("只读"));
        // 回退路径放行：不可写即可（Windows 开发机语义）
        FileSecurityProbe fixed = new FakeFiles(true, Optional.empty(), false);
        assertThat(run(Map.of(), goodDb(), "app", fixed, KEY_PATH)).isEmpty();
    }

    @Test
    void rejectsInsertPrivilegeOnOutbox() {
        DbPrivilegeProbe wrong = (table, privilege) -> true; // INSERT 也有 = 能伪造写意图
        assertThat(run(Map.of(), wrong, "app", readOnlyKeyFile(), KEY_PATH))
                .anySatisfy(v -> assertThat(v).contains("INSERT"));
    }

    @Test
    void rejectsMissingUpdatePrivilegeOnOutbox() {
        DbPrivilegeProbe wrong = (table, privilege) -> false; // 无 UPDATE = 配错
        assertThat(run(Map.of(), wrong, "app", readOnlyKeyFile(), KEY_PATH))
                .anySatisfy(v -> assertThat(v).contains("UPDATE"));
    }

    @Test
    void runnerRefusesStartupOnViolations() {
        StartupSelfCheckRunner runner = new StartupSelfCheckRunner("publisher",
                () -> List.of("私钥可写"));
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("启动自检失败").hasMessageContaining("私钥可写");
    }

    @Test
    void runnerPassesWhenNoViolations() throws Exception {
        new StartupSelfCheckRunner("publisher", List::of).run(new DefaultApplicationArguments());
    }
}
