package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RunState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewRunRepairTest {

    @Test
    void zeroStepRepairRunCanFinishDirectly() {
        // RM2-10：REPAIR Run 真实铸单形态是 publisherDisabled=false（要发布 repair 命令）
        ReviewRun run = run(RunMode.REPAIR, false);
        Instant completedAt = Instant.parse("2026-08-31T08:00:00Z");

        run.finishRepair(RunState.COMPLETED, completedAt);

        assertEquals(RunState.COMPLETED, run.getState());
        assertEquals(completedAt, run.getCompletedAt());
    }

    @Test
    void repairRunMayEnablePublishing() {
        ReviewRun run = run(RunMode.REPAIR, false);

        assertFalse(run.isPublisherDisabled());
    }

    @Test
    void replayFamilyStillRequiresPublisherDisabled() {
        // V4 ck_replay_publisher_disabled 修订后边界不变：回放/重建类仍禁止发布
        for (RunMode mode : new RunMode[]{RunMode.PROJECTION_REBUILD, RunMode.RECORDED_REPLAY,
                RunMode.ISOLATED_REEXECUTION}) {
            assertThrows(IllegalArgumentException.class, () -> run(mode, false));
            run(mode, true); // publisher_disabled=true 仍合法
        }
    }

    @Test
    void normalRunCannotUseRepairShortcut() {
        assertThrows(IllegalStateException.class,
                () -> run(RunMode.NORMAL, false).finishRepair(RunState.COMPLETED, Instant.now()));
    }

    private static ReviewRun run(RunMode mode, boolean publisherDisabled) {
        Instant now = Instant.parse("2026-08-31T07:00:00Z");
        return new ReviewRun(UUID.randomUUID(), UUID.randomUUID(), null, null,
                Digest.sha256Of(UUID.randomUUID().toString()), "repair:test", mode,
                "policy", "prompt", "toolset", null, RunState.CREATED,
                publisherDisabled, null, null, null, 0, now, now, null);
    }
}
