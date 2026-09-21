import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.PrimaryClaimAdmission;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.evidence.*;
import com.objwww.pr.notify.application.NotifyConfig;
import com.objwww.pr.notify.domain.channel.*;
import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.service.*;
import org.springframework.core.env.StandardEnvironment;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline characterization: assertions confirm defects, not acceptance of correct behavior. */
public class CurrentBoundaryAudit {
    static final UUID RUN = UUID.randomUUID(), REF = UUID.randomUUID();
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("REPRODUCED: " + message);
    }
    static EvidenceRepository evidence(boolean present) {
        String payload = "{\"data\":{\"severity\":\"ERROR\",\"result\":[{\"count\":7}]}}";
        EvidenceEnvelope row = new EvidenceEnvelope(REF, RUN, UUID.randomUUID(),
                "logs.aggregate", "am4-evidence.v1", 1, "loki", Map.of(), NOW, NOW,
                payload, com.objwww.pr.shared.Digests.sha256Hex(payload));
        return (EvidenceRepository) Proxy.newProxyInstance(CurrentBoundaryAudit.class.getClassLoader(),
                new Class[]{EvidenceRepository.class}, (p, method, args) -> switch (method.getName()) {
                    case "findById" -> present ? Optional.of(row) : Optional.empty();
                    case "findByRunId" -> present ? List.of(row) : List.of();
                    default -> null;
                });
    }
    static PrimaryClaimAdmission.AdmittedClaim claim(boolean present, String locator) {
        var c = new PrimaryDecision.FinalClaim("c", "ROOT_CAUSE", "test proposition",
                List.of(REF.toString()), List.of(new PrimaryDecision.EvidenceRole(
                        REF.toString(), "SUPPORTS", locator)));
        return PrimaryClaimAdmission.admit(List.of(c), Set.of(REF.toString()),
                evidence(present), RUN).claims().getFirst();
    }
    static ClaimedNotification notification(Instant created) {
        UUID op = UUID.randomUUID();
        return new ClaimedNotification(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "missing", "1", op, "{\"operation_id\":\"" + op + "\"}", 0, 3, 1, created);
    }
    static FencedNotifyExecutor executor(FencedNotifyExecutor.ChannelRouter router,
                                         boolean validateJson) {
        NotifyOutboxStore store = (NotifyOutboxStore) Proxy.newProxyInstance(
                CurrentBoundaryAudit.class.getClassLoader(), new Class[]{NotifyOutboxStore.class},
                (p, method, args) -> {
                    if (validateJson && method.getName().equals("markRetryWait")) {
                        try { JSON.readTree((String) args[4]); }
                        catch (Exception e) { throw new IllegalArgumentException("INVALID_ERROR_JSON", e); }
                    }
                    return null;
                });
        return new FencedNotifyExecutor(store, new NotificationRenderer(500, 3800), router,
                (n, from, retryAfter) -> from.plusSeconds(30), () -> NOW, Duration.ofHours(24));
    }
    public static void main(String[] args) {
        check(claim(false, "data.result.0.count").hasSupport(),
                "missing evidence row retains SUPPORTS");
        var invalid = claim(true, "data.absent");
        check(invalid.hasSupport() && invalid.refVerdicts().getFirst().locator() == null,
                "unresolved locator retains SUPPORTS / ROOT_CAUSE");
        try {
            claim(true, "data.result.2147483648.count");
            throw new AssertionError("overflow did not throw");
        } catch (NumberFormatException expected) {
            check(true, "model locator index overflow escapes admission");
        }
        var router = new NotifyConfig().channelRouter("", new StandardEnvironment(), null);
        try {
            executor(router, false).execute(notification(NOW));
            throw new AssertionError("missing channel did not throw");
        } catch (NullPointerException expected) {
            check(true, "actual config missing-channel lookup causes NPE before terminal record");
        }
        AtomicInteger sends = new AtomicInteger();
        var outcome = executor(name -> (body, id) -> {
            sends.incrementAndGet();
            return new NotificationChannel.SendResult.Delivered();
        }, false).execute(notification(NOW.minus(Duration.ofDays(2))));
        check(sends.get() == 1 && outcome == FencedNotifyExecutor.Outcome.SENT,
                "48-hour-old notification is sent despite 24-hour max age");
        var failure = WebhookTransport.Classifier.fromStatus(200, null,
                "{\"errcode\":123,\"errmsg\":\"bad \\\"field\\\"\\nsecond line\"}");
        try {
            executor(name -> (body, id) -> failure, true).execute(notification(NOW));
            throw new AssertionError("invalid JSON did not throw");
        } catch (IllegalArgumentException expected) {
            if (!"INVALID_ERROR_JSON".equals(expected.getMessage())) throw expected;
            check(true, "valid platform error response produces invalid last_error JSON");
        }
        System.out.println("6 defects characterized; no network or database was used.");
    }
}
