import com.objwww.pr.control.eval.application.*;
import com.objwww.pr.control.eval.domain.*;
import com.objwww.pr.control.eval.domain.model.*;
import com.objwww.pr.control.eval.domain.repository.*;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.drill.application.*;
import com.objwww.pr.shared.Digest;
import org.springframework.mock.web.*;
import org.springframework.security.web.csrf.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;

/** Local defect characterization. No server, database, model or real injection is used. */
public class EvalDrillSecurityAudit {
    static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    static final Digest DIGEST = Digest.sha256Of("local-safety-audit");
    static GoldenScenarioRegistry registry() {
        return GoldenScenarioRegistry.load("""
            registry_version: 1
            schema_version: 1
            scenarios:
              - scenario_id: S1
                name: local-audit
                driver: FlagdScenarioDriver
                target: payment
                expected_root_cause:
                  component: payment
                  fault_type: BUSINESS_ERROR_RATE
                  reason_code: PAYMENT_CHARGE_FAILURE
                expected_symptom_codes: []
                injection:
                  flag: paymentFailure
                  variant: injected
                  baseline_variant: baseline
                timing:
                  preheat_seconds: 1
                  hold_seconds: 1
                  max_firing_wait_seconds: 1
                  max_resolved_wait_seconds: 1
                  cleanup_timeout_seconds: 1
            """);
    }
    static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "ds", DIGEST, 1, "model", "prompt", DIGEST, DIGEST,
            null, null, 1000, null, null, "provider", DIGEST, "driver", "grader");
    }
    static EvalBatchRunner.EvalClock clock() {
        return new EvalBatchRunner.EvalClock() {
            public Instant now() { return NOW; }
            public void sleepSeconds(long s) {}
        };
    }
    static boolean csrf(String submitted) throws Exception {
        var ctor = Class.forName("com.objwww.pr.control.infrastructure.config.SecurityConfig$SpaCsrfTokenRequestHandler").getDeclaredConstructor();
        ctor.setAccessible(true);
        var handler = (CsrfTokenRequestHandler) ctor.newInstance();
        var filter = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
        filter.setRequestHandler(handler);
        var request = new MockHttpServletRequest("POST", "/api/drills");
        request.setCookies(new Cookie("XSRF-TOKEN", "expected-test-token"));
        if (submitted != null) request.addHeader("X-XSRF-TOKEN", submitted);
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> continued.set(true));
        System.out.println("SAFE-01 submitted=" + submitted + " csrfChainContinued=" + continued.get() + " status=" + response.getStatus());
        return continued.get();
    }
    public static void main(String[] args) throws Exception {
        if (csrf(null) || !csrf("expected-test-token") || !csrf("wrong-test-token"))
            throw new AssertionError("CSRF characterization changed");

        var reg = registry();
        var phaseSink = mock(EvalPhaseEventSink.class);
        var commands = mock(EvalRunCommandRepository.class);
        var driver = mock(ScenarioDriver.class);
        var runs = mock(EvalRunRepository.class);
        when(runs.insertCaseResult(any())).thenReturn(true);
        var generator = spy(new BaselineReportGenerator());
        when(driver.activate(any(), anyInt())).thenReturn(new ScenarioDriver.ActivationReceipt("S1", "action", 1, "alert"));
        when(driver.deactivate(any(), any())).thenThrow(new IllegalStateException("restore transport unavailable"));
        var gate = EvalLaunchGate.closed(Set.of("L"), "ds", 1, 10);
        var executor = new EvalLaunchExecutor(reg, Map.of("FlagdScenarioDriver", driver),
            mock(AlertProbe.class), mock(IncidentResolutionProbe.class), mock(RcaRunResolver.class),
            mock(SingleCaseScorer.class), runs, generator, phaseSink, commands,
            metadata(), 1, clock(), "offline", gate);
        String payload = "{\"displayName\":\"audit\",\"mode\":\"L\",\"datasetVersion\":\"ds\",\"roundsPerScenario\":1}";
        var command = EvalRunCommand.pending(UUID.randomUUID(), EvalRunCommand.Type.LAUNCH,
            UUID.randomUUID(), "audit", payload, DIGEST.value(), "auditor", NOW);
        executor.execute(command);
        verify(driver).activate(any(), eq(1));
        System.out.println("SAFE-02 allowed L launch reached injection through real executor; no drill occupancy port exists in this execution path");
        var terminal = org.mockito.ArgumentCaptor.forClass(EvalRun.class);
        verify(runs).finalizeOnce(terminal.capture());
        if (terminal.getValue().state() != EvalRun.EvalRunState.SUCCEEDED) throw new AssertionError("terminal changed");
        verify(runs).updateRecoveryState(command.evalRunId(), "PENDING");
        verify(runs, never()).updateRecoveryState(command.evalRunId(), "FAILED");
        verify(runs, never()).updateRecoveryState(command.evalRunId(), "VERIFIED");
        System.out.println("SAFE-05 deactivation throws but run=SUCCEEDED and recovery state is left PENDING");

        reset(driver, runs);
        when(runs.insertCaseResult(any())).thenReturn(true);
        when(driver.activate(any(), anyInt())).thenThrow(new ArenaChaosScenarioDriver.ActivationException(
            "traffic failed after activation", new ScenarioDriver.ActivationReceipt("chaos-eval-s1-r1", "action", 1, "alert"), new IllegalStateException("traffic")));
        executor.execute(command);
        verify(driver, never()).deactivate(any(), any());
        System.out.println("SAFE-05 activation exception carries a live receipt, but EvalBatchRunner never calls deactivate");

        var flagClient = mock(FlagdScenarioDriver.FlagAdminClient.class);
        when(flagClient.readDefaultVariant("paymentFailure")).thenReturn(new FlagdState("baseline", "before"), new FlagdState("injected", "after"));
        when(flagClient.setDefaultVariant("paymentFailure", "injected")).thenReturn("injected");
        var ledger = mock(FlagdRestoreLedger.class);
        doThrow(new IllegalStateException("ledger unavailable")).when(ledger).recordActivation(any());
        var realDriver = new FlagdScenarioDriver(flagClient, mock(AlertProbe.class), ledger, Clock.fixed(NOW, ZoneOffset.UTC));
        try { realDriver.activate(reg.scenarios().getFirst(), 1); throw new AssertionError("must throw"); }
        catch (IllegalStateException expected) {
            if (!expected.getMessage().equals("ledger unavailable")) throw expected;
        }
        var order = inOrder(flagClient, ledger);
        order.verify(flagClient).setDefaultVariant("paymentFailure", "injected");
        order.verify(ledger).recordActivation(any());
        System.out.println("SAFE-03 flag write completes before journal insertion fails; no durable activation receipt was written");
        var current = new String[]{"injected"};
        var racingPort = new FlagdAdminPort() {
            public FlagdState read(String flag) {
                var observed = new FlagdState(current[0], "my-generation");
                current[0] = "operator-new-value";
                return observed;
            }
            public String write(String flag, String value) { current[0] = value; return value; }
        };
        var restored = FlagdConditionalRestore.attempt(racingPort, "paymentFailure", "injected", "my-generation", "baseline");
        if (!"baseline".equals(current[0]) || restored.outcome() != FlagdConditionalRestore.Outcome.RESTORED)
            throw new AssertionError("restore race changed");
        System.out.println("SAFE-03 another writer changes the flag after read; restore overwrites it and reports RESTORED (no server CAS)");

        var jobs = mock(DrillJobRepository.class);
        var job = mock(DrillJob.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.scenarioId()).thenReturn("S3");
        when(job.state()).thenReturn(DrillJob.State.OBSERVING);
        when(job.stopRequestedAt()).thenReturn(NOW);
        when(jobs.findActiveInStates(anyList())).thenReturn(List.of(job));
        when(jobs.claimNext(anyString(), any())).thenReturn(Optional.empty());
        var template = mock(DrillTemplate.class);
        when(template.driver()).thenReturn("ArenaChaosScenarioDriver");
        when(template.symptomCodes()).thenReturn(List.of());
        var catalog = mock(DrillTemplateCatalog.class);
        when(catalog.byScenarioId("S3")).thenReturn(Optional.of(template));
        var sweeper = mock(FlagdRestoreSweeper.class);
        var drillClock = new DrillWorker.DrillClock() {
            public Instant now() { return NOW; }
            public void sleepSeconds(long s) {}
        };
        var worker = new DrillWorker(jobs, mock(DrillEventRepository.class), catalog,
            mock(DrillInjectionPort.class), drillClock, List.of("arena-195"), "offline-drill", 5, 900,
            DrillCorrelationPort.disabled(), sweeper);
        worker.tick();
        verify(job, never()).stopRequestedAt();
        System.out.println("SAFE-04 OBSERVING drill with stopRequestedAt is scanned but stop intent is never read by worker.tick");
        System.out.println("All characterization checks completed. Defects reproduced, not acceptance PASS; zero real I/O.");
    }
}
