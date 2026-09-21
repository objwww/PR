import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.drill.application.*;
import com.objwww.pr.control.drill.domain.model.*;
import com.objwww.pr.control.drill.domain.repository.*;
import com.objwww.pr.control.eval.application.*;
import com.objwww.pr.control.eval.domain.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

/** Offline review probe against d9990495. Fake ports only; no DB/network/injection.
 * Reproduces current unsafe outcomes, so SUCCESS here is not product acceptance. */
public class SafeClosedBoundaryAudit {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Object fixture(String name) throws Exception {
        var c = Class.forName("com.objwww.pr.control.drill.application.DrillWorkerTest$" + name)
                .getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        drill();
        once("once");
        once("workre");
        compare("RUNNING", 5, 5, false);
        compare("FAILED", 5, 5, false);
        compare("SUCCEEDED", 50, 5, false);
        compare("SUCCEEDED", 5, 5, true);
        System.out.println("AUDIT COMPLETE: observed boundaries reproduced; not production PASS");
    }
    private static void drill() throws Exception {
        var jobs = (DrillJobRepository) fixture("FakeJobs");
        var events = (DrillEventRepository) fixture("FakeEvents");
        var clock = (DrillWorker.DrillClock) fixture("FixedClock");
        var method = Class.forName("com.objwww.pr.control.drill.application.DrillWorkerTest")
                .getDeclaredMethod("catalog", boolean.class);
        method.setAccessible(true);
        var catalog = (DrillTemplateCatalog) method.invoke(null, true);
        var service = new DrillJobService(jobs, events, catalog, MAPPER,
                List.of("arena-195"), false);
        var plan = new DrillLaunchPlan("T1", "arena-195", null, null, null);
        var preview = service.preview(plan);
        var refused = service.create(plan, "audit-closed", "audit");
        System.out.println("CLOSE-PREVIEW ready=" + preview.template().execution().get("ready")
                + " canLaunch=" + preview.canLaunch() + " create=" + refused.status());
        require(preview.canLaunch(), "expected current closed-preview discrepancy");
        require(refused.status() == DrillJobService.CreateStatus.LAUNCH_DISABLED,
                "API close regression");
        var job = DrillJob.queued(UUID.randomUUID(), "T1", "test", catalog.contentDigest().value(),
                "arena-195", "audit", "{}", "1".repeat(64), "old-queued", clock.now());
        jobs.insert(job); // represents a job accepted BEFORE launch was disabled
        var calls = new AtomicInteger();
        DrillInjectionPort port = j -> {
            calls.incrementAndGet();
            return DrillInjectionPort.Outcome.notPerformed("offline probe only");
        };
        new DrillWorker(jobs, events, catalog, port, clock, List.of("arena-195"),
                "audit", 5, 900).tick();
        require(calls.get() == 1, "expected current queued worker bypass");
        System.out.println("CLOSE-BACKLOG apiLaunchEnabled=false injectionPortCalls=" + calls.get());
    }
    private static final class StopBeforeSideEffect extends Error {}
    private static void once(String mode) {
        var runner = mock(EvalBatchRunner.class);
        when(runner.runBatch()).thenThrow(new StopBeforeSideEffect());
        try {
            new EvalRunnerMain(runner, null, null, null, null, mode).run(null);
            throw new AssertionError("expected sentinel");
        } catch (StopBeforeSideEffect expected) {
            verify(runner).runBatch();
            System.out.println("CLOSE-CLI mode=" + mode + " directRunBatch=true (stopped at fake port)");
        }
    }
    private static void compare(String state, int baselineN, int candidateN, boolean missingDigest) {
        var reader = mock(EvalQueryReader.class);
        var records = mock(EvalComparisonRepository.class);
        var base = UUID.nameUUIDFromBytes("baseline".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var cand = UUID.nameUUIDFromBytes("candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(reader.findCompareMeta(base)).thenReturn(Optional.of(meta(base, "SUCCEEDED")));
        when(reader.findCompareMeta(cand)).thenReturn(Optional.of(meta(cand, state)));
        when(reader.listCasesForCompare(base, 10001)).thenReturn(rows(baselineN, missingDigest));
        when(reader.listCasesForCompare(cand, 10001)).thenReturn(rows(candidateN, missingDigest));
        var service = new EvalCompareService(reader, records, MAPPER);
        var result = service.record(base, cand, "offline-audit").orElseThrow();
        require("PASS".equals(result.gate().outcome()), "unexpected gate " + result.gate());
        verify(records).insert(any());
        System.out.println("COMPARE candidateState=" + state + " baselineCases=" + baselineN
                + " candidateCases=" + candidateN + " paired=" + result.summary().pairedCount()
                + " unpaired=" + result.summary().unpairedCount() + " missingDigest=" + missingDigest
                + " gate=" + result.gate().outcome() + " persisted=true");
    }
    private static EvalQueryReader.CompareRunMeta meta(UUID id, String state) {
        return new EvalQueryReader.CompareRunMeta(id, "dataset-1", "registry-1", "rules-1",
                1, "driver-1", "model", "prompt", "config", state);
    }
    private static List<EvalQueryReader.CompareCaseRow> rows(int n, boolean missingDigest) {
        var rows = new ArrayList<EvalQueryReader.CompareCaseRow>();
        for (int i = 0; i < n; i++) rows.add(new EvalQueryReader.CompareCaseRow(UUID.randomUUID(),
                "S" + i, 1, "TP", true, "{}", "selection-v1", missingDigest ? null : "digest-" + i,
                "family-" + i, null, 10L));
        return rows;
    }
}
