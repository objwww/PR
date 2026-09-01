package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.infrastructure.persistence.PostgresStepCheckpointRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CT-23/CT-26：真 PG 唯一覆盖语义与旧 Worker 晚到写栅栏。 */
class CT23CheckpointFenceIT extends PostgresITBase {

    private UUID stepId;
    private UUID workItemId;
    private PostgresStepCheckpointRepository repository;

    @BeforeEach
    void seedLease() {
        UUID subjectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        stepId = UUID.randomUUID();
        workItemId = UUID.randomUUID();
        String d = "a".repeat(64);
        adminJdbc.sql("""
                INSERT INTO pr_subject(id,github_installation_id,github_repository_id,repository_full_name,
                    pr_number,state,draft,merged,current_policy_version,publication_epoch,
                    next_outbox_sequence,last_resolved_sequence,version,created_at,updated_at)
                VALUES (:s,1,1,'octo/demo',1,'OPEN',false,false,'p',1,1,0,0,now(),now())
                """).param("s", subjectId).update();
        adminJdbc.sql("""
                INSERT INTO pr_revision(id,pr_subject_id,head_sha,base_ref,base_sha,diff_digest,
                    revision_fingerprint,observed_at,created_at)
                VALUES (:r,:s,'head','main','base',:d,:d,now(),now())
                """).param("r", revisionId).param("s", subjectId).param("d", d).update();
        adminJdbc.sql("UPDATE pr_subject SET current_revision_id=:r WHERE id=:s")
                .param("r", revisionId).param("s", subjectId).update();
        adminJdbc.sql("""
                INSERT INTO review_run(id,pr_revision_id,run_key,trigger_key,run_mode,policy_version,
                    prompt_version,toolset_version,state,publisher_disabled,version,created_at,updated_at)
                VALUES (:id,:r,:key,'test','NORMAL','p','prompt','tools','REVIEWING',false,0,now(),now())
                """).param("id", runId).param("r", revisionId)
                .param("key", Digest.sha256Of(runId.toString()).value()).update();
        adminJdbc.sql("""
                INSERT INTO run_step(id,review_run_id,step_key,operation_id,step_type,state,ordinal,
                    max_attempts,timeout_seconds,version,created_at,updated_at)
                VALUES (:id,:run,'review',:op,'REVIEW','RUNNING',1,3,600,0,now(),now())
                """).param("id", stepId).param("run", runId).param("op", UUID.randomUUID()).update();
        adminJdbc.sql("""
                INSERT INTO work_item(id,review_run_id,step_id,work_type,state,priority,available_at,
                    lease_owner,lease_until,lease_epoch,attempt_count,max_attempts,created_at,updated_at)
                VALUES (:id,:run,:step,'REVIEW','LEASED',0,now(),'worker-a',now()+interval '10 minutes',
                    1,1,3,now(),now())
                """).param("id", workItemId).param("run", runId).param("step", stepId).update();
        repository = new PostgresStepCheckpointRepository(controlJdbc);
    }

    @Test
    void sameKeyOverwritesAndDifferentKeyCoexists() {
        StepCheckpoint first = checkpoint("REVIEW_OUTCOME", "one", 1);
        StepCheckpoint second = checkpoint("REVIEW_OUTCOME", "two", 1);
        StepCheckpoint other = checkpoint("MODEL_SUMMARY", "three", 1);

        assertThat(repository.upsertIfLeaseCurrent(first, workItemId, "worker-a")).isTrue();
        assertThat(repository.upsertIfLeaseCurrent(second, workItemId, "worker-a")).isTrue();
        assertThat(repository.upsertIfLeaseCurrent(other, workItemId, "worker-a")).isTrue();

        assertThat(count("step_checkpoint")).isEqualTo(2);
        assertThat(repository.find(stepId, "REVIEW_OUTCOME").orElseThrow().outputArtifactDigest())
                .isEqualTo(Digest.sha256Of("two"));
    }

    @Test
    void oldLeaseCannotOverwriteNewCheckpoint() {
        StepCheckpoint old = checkpoint("REVIEW_OUTCOME", "old", 1);
        assertThat(repository.upsertIfLeaseCurrent(old, workItemId, "worker-a")).isTrue();
        adminJdbc.sql("""
                UPDATE work_item SET lease_owner='worker-b',lease_epoch=2,
                    lease_until=now()+interval '10 minutes',updated_at=now() WHERE id=:id
                """).param("id", workItemId).update();
        StepCheckpoint current = checkpoint("REVIEW_OUTCOME", "current", 2);

        assertThat(repository.upsertIfLeaseCurrent(old, workItemId, "worker-a")).isFalse();
        assertThat(repository.upsertIfLeaseCurrent(current, workItemId, "worker-b")).isTrue();
        assertThat(repository.find(stepId, "REVIEW_OUTCOME").orElseThrow().outputArtifactDigest())
                .isEqualTo(Digest.sha256Of("current"));
    }

    private StepCheckpoint checkpoint(String key, String content, long epoch) {
        return new StepCheckpoint(UUID.randomUUID(), stepId, key,
                Digest.sha256Of(content), Digest.sha256Of("model-" + content),
                Digest.sha256Of("contract"), "prompt", "schema", "mapper", "context", "model",
                epoch, (int) epoch, Instant.now());
    }
}
