import com.objwww.pr.control.eval.application.*;
import com.objwww.pr.control.eval.domain.*;
import com.objwww.pr.control.eval.domain.model.*;
import com.objwww.pr.control.eval.domain.repository.*;
import com.objwww.pr.control.ops.application.MetricsWhitelistService;
import com.objwww.pr.shared.Digest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;

/** Offline characterization. All execution ports are mocks; no model, DB or fault injection.
 *  SUPERSEDED 2026-09-14: the PAGE-03/04 defect assertions are EXPECTED TO FAIL after the
 *  capability-gate + frozen-intent repairs; post-fix coverage lives in EvalLaunchGateTest,
 *  EvalLaunchExecutorGateTest and EvalCommandServiceTest (PAGE-03/10). */
public class PageExecutionAudit {
    static final class StopAtActivation extends Error {}
    public static void main(String[] args) {
        GoldenScenarioRegistry registry = GoldenScenarioRegistry.load("""
            registry_version: 1
            schema_version: 1
            scenarios:
              - scenario_id: S1
                name: audit
                driver: FlagdScenarioDriver
                target: payment
                expected_root_cause:
                  component: payment
                  fault_type: BUSINESS_ERROR_RATE
                  reason_code: PAYMENT_CHARGE_FAILURE
                expected_symptom_codes: []
                timing:
                  preheat_seconds: 0
                  hold_seconds: 0
                  max_firing_wait_seconds: 0
                  max_resolved_wait_seconds: 0
                  cleanup_timeout_seconds: 0
            """);
        Digest digest = Digest.sha256Of("offline-audit");
        EvalRunMetadata metadata = new EvalRunMetadata(1, "actual-dataset", digest, 1,
            "actual-model", "actual-prompt", digest, digest, null, null, 1000, null, null,
            "actual-provider", digest, "driver-v1", "grader-v1");
        EvalBatchRunner.EvalClock clock = new EvalBatchRunner.EvalClock() {
            public Instant now() { return Instant.parse("2026-09-14T04:00:00Z"); }
            public void sleepSeconds(long seconds) { throw new AssertionError("must stop before wait"); }
        };
        for (String mode : new String[]{"E", "B"}) {
            ScenarioDriver driver = mock(ScenarioDriver.class);
            when(driver.activate(any(), anyInt())).thenThrow(new StopAtActivation());
            EvalLaunchExecutor executor = new EvalLaunchExecutor(registry,
                Map.of("FlagdScenarioDriver", driver), mock(AlertProbe.class),
                mock(IncidentResolutionProbe.class), mock(RcaRunResolver.class),
                mock(SingleCaseScorer.class), mock(EvalRunRepository.class),
                mock(BaselineReportGenerator.class), mock(EvalPhaseEventSink.class),
                mock(EvalRunCommandRepository.class), metadata, 1, clock, "offline-worker");
            String payload = "{\"displayName\":\"offline-audit\",\"mode\":\"" + mode
                + "\",\"datasetVersion\":\"claimed-other-dataset\",\"roundsPerScenario\":1}";
            EvalRunCommand command = EvalRunCommand.pending(UUID.randomUUID(), EvalRunCommand.Type.LAUNCH,
                UUID.randomUUID(), "offline-" + mode, payload, digest.value(), "auditor", clock.now());
            boolean reached = false;
            try { executor.execute(command); } catch (StopAtActivation expected) { reached = true; }
            if (!reached) throw new AssertionError("characterization changed: " + mode);
            verify(driver).activate(any(), eq(1));
            System.out.println("PAGE-03 mode=" + mode + " reached ScenarioDriver.activate through actual EvalLaunchExecutor; no real I/O");
        }
        EvalLaunchPlan first = new EvalLaunchPlan("same", "E", "v1", null, null, null, 1, 3600L, 1);
        EvalLaunchPlan retry = new EvalLaunchPlan("same", "E", "v1", null, null, null, 1, 3590L, 1);
        if (first.payloadHash().equals(retry.payloadHash())) throw new AssertionError("hash unexpectedly equal");
        System.out.println("PAGE-04 server payload hashes differ for deadlineSeconds=3600 and 3590");
        MetricsWhitelistService metrics = new MetricsWhitelistService((q, s, e, step) -> """
            {"status":"success","data":{"resultType":"matrix","result":[
                {"metric":{"instance":"host-a"},"values":[[100,"NaN"],[160,"+Inf"]]}
            ]}}
            """, clock::now);
        var response = metrics.queryRange("host_cpu_usage", "100", "200", "60");
        if (response.series().size() != 1 || !response.series().getFirst().points().isEmpty())
            throw new AssertionError("empty-series characterization changed");
        System.out.println("PAGE-08 all invalid metric points produce series=1, validPoints=0");
        System.out.println("Characterization complete; these results expose defects, not acceptance PASS.");
    }
}
