package com.objwww.pr.control.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RevisionService;

/**
 * T1/T2 事务脚本单测的公共夹具：全套内存假实现 + 真实 OutboxWriter/ExecutionLedger/RevisionService。
 */
public final class OrchestratorFixture {

    public final InMemoryStores.Subjects subjects = new InMemoryStores.Subjects();
    public final InMemoryStores.Revisions revisions = new InMemoryStores.Revisions();
    public final InMemoryStores.Runs runs = new InMemoryStores.Runs(revisions);
    public final InMemoryStores.Steps steps = new InMemoryStores.Steps();
    public final InMemoryStores.WorkItems workItems = new InMemoryStores.WorkItems(steps);
    public final InMemoryStores.Attempts attempts = new InMemoryStores.Attempts();
    public final InMemoryStores.Findings findings = new InMemoryStores.Findings();
    public final InMemoryStores.OutboxCommands outbox = new InMemoryStores.OutboxCommands();
    public final InMemoryStores.Events events = new InMemoryStores.Events();
    public final InMemoryStores.Artifacts artifacts = new InMemoryStores.Artifacts();
    public final InMemoryStores.Cas cas = new InMemoryStores.Cas();
    public final InMemoryStores.Checkpoints checkpoints = new InMemoryStores.Checkpoints(workItems);
    public final InMemoryStores.Sequences sequences = new InMemoryStores.Sequences(subjects);
    public final RevisionService revisionService = new RevisionService();
    public final ExecutionLedger ledger = new ExecutionLedger(events);
    public final OutboxWriter outboxWriter = new OutboxWriter(outbox, sequences, cas, artifacts);
    public final ReviewOrchestrator orchestrator = new ReviewOrchestrator(
            subjects, revisions, runs, steps, workItems, attempts, findings,
            revisionService, ledger, outboxWriter, new ObjectMapper());
}
