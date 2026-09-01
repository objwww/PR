package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.CheckpointWriter;
import com.objwww.pr.control.application.LeaseHeartbeat;
import com.objwww.pr.control.application.ReviewStepExecutor;
import com.objwww.pr.control.application.StepExecutionContext;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.service.CheckpointResumeService;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;

/**
 * ST-28 用：记录 Worker 内部产生的 {@link StepOutcome}（含 ReviewOutcome 对象图），
 * 使测试能在"T2 已提交、Worker 应答前崩溃"后，以崩溃前同一份结果重放 T2——
 * 验证既有幂等面（finding 唯一约束 / attempt 终态机 / outbox 不重复铸命令）。
 */
final class StCheckpointRecordingExecutor extends ReviewStepExecutor {

    private volatile StepOutcome lastOutcome;

    StCheckpointRecordingExecutor(ReviewRunRepository runRepository,
                                  PRRevisionRepository revisionRepository,
                                  ArtifactStore artifactStore, ArtifactRepository artifactRepository,
                                  SafeTarExtractor extractor, ReviewAgentLoop agentLoop,
                                  ReviewBudget budget, ObjectMapper objectMapper,
                                  CheckpointResumeService resumeService,
                                  CheckpointWriter checkpointWriter,
                                  ExecutionLedger ledger, String modelIdentity) {
        super(runRepository, revisionRepository, artifactStore, artifactRepository, extractor,
                agentLoop, budget, objectMapper, resumeService, checkpointWriter, ledger,
                modelIdentity);
    }

    @Override
    public StepOutcome execute(StepExecutionContext context, LeaseHeartbeat heartbeat) {
        StepOutcome outcome = super.execute(context, heartbeat);
        this.lastOutcome = outcome;
        return outcome;
    }

    StepOutcome lastOutcome() {
        return lastOutcome;
    }
}
