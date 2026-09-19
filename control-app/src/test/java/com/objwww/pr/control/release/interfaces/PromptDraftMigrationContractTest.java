package com.objwww.pr.control.release.interfaces;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-d T7 草稿面本地静态门（范式沿 RcaClaimMigrationContractTest）：锁 V153 状态机
 * 与控制器 SQL 的三条纪律——草稿非资产（不写 release_asset）、状态机单向、发布绕行
 * 零端点（发布只归既有受控激活）。
 */
class PromptDraftMigrationContractTest {

    private static String normalized(Path p) throws IOException {
        return Files.readString(p).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v153StateMachinesAreFrozen() throws IOException {
        String sql = normalized(Path.of(
                "src/main/resources/db/migration/V153__md_prompt_draft.sql"));
        // 状态机封闭 + APPLIED 必须回填资产摘要（对账锚不可缺席）
        assertThat(sql)
                .contains("check (status in ('draft', 'discarded', 'applied'))")
                .contains("check (status <> 'applied' or applied_asset_digest is not null)")
                .contains("check (length(proposed_template) > 0)");
    }

    @Test
    void controllerWritesDraftTableOnlyAndNeverReleaseAsset() throws IOException {
        String code = normalized(Path.of(
                "src/main/java/com/objwww/pr/control/release/interfaces/PromptDraftController.java"));
        // 草稿面只落 prompt_draft；insert into release_asset = 绕行发布（禁止面）
        assertThat(code)
                .contains("insert into prompt_draft")
                .doesNotContain("insert into release_asset");
        // 裁定 CAS 单向：仅 DRAFT → DISCARDED
        assertThat(code).contains("where id = :id and status = 'draft'");
    }

    @Test
    void controllerExposesNoPublishEndpoint() throws IOException {
        String code = normalized(Path.of(
                "src/main/java/com/objwww/pr/control/release/interfaces/PromptDraftController.java"));
        // §7 安全边界：发布不做绕行直发端点（apply/publish/promote 全数缺席）
        assertThat(code)
                .doesNotContain("mapping(\"/drafts/{id}/apply")
                .doesNotContain("mapping(\"/drafts/{id}/publish")
                .doesNotContain("mapping(\"/drafts/{id}/promote");
    }
}
