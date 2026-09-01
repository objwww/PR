package com.objwww.pr.control.it;

import com.objwww.pr.control.application.CheckpointWriter;
import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.StepCheckpointRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.shared.ExecutionEvent;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ST-26 用：checkpoint 短事务入口崩溃注入——双 artifact 已落 CAS，{@code store}
 * 首次调用在事务做任何事之前抛 {@link StCheckpointHarness.SimulatedCrash}
 * （一次性，重放路径恢复委托）。
 *
 * <p>覆写方法保留 {@link Transactional}：线束以 CGLIB 代理本类，事务语义与
 * 生产装配一致（炸在代理事务刚开启、首条 SQL 之前的等价位置）。
 * 本类不得声明 final（CGLIB 需要子类化，TB-08）。
 */
class StCheckpointCrashCheckpointWriter extends CheckpointWriter {

    private final AtomicBoolean armed = new AtomicBoolean(true);

    StCheckpointCrashCheckpointWriter(ArtifactRepository artifacts,
                                      StepCheckpointRepository checkpoints, ExecutionLedger ledger) {
        super(artifacts, checkpoints, ledger);
    }

    @Override
    @Transactional
    public boolean store(ArtifactRecord output, ArtifactRecord model, StepCheckpoint checkpoint,
                         UUID workItemId, String leaseOwner, ExecutionEvent storedEvent) {
        if (armed.compareAndSet(true, false)) {
            throw new StCheckpointHarness.SimulatedCrash("双 artifact 落 CAS 后、checkpoint 事务内首写前");
        }
        return super.store(output, model, checkpoint, workItemId, leaseOwner, storedEvent);
    }
}
