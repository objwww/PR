package com.objwww.pr.control.alert.domain.identity;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 调查输入材料（EX-A0 F14）：Run 铸造时一次冻结——episode 身份 + 冻结时间窗 +
 * 查询参数。铸点（IncidentProjector/RcaRunOrchestrator.castRunAndTask）经
 * {@link #freezeAt} 构造并随 run 行落 {@link #inputDigest()} 与窗口列（V36）；
 * 执行期只读（禁止静默改取"执行时最近十分钟"，"现在是否恢复"归 R7 显式动作）。
 *
 * <p>身份格式（F23 一次定好）：canonical 形态 schemaVersion={@value #SCHEMA_VERSION}
 * 走 {@link InternalCanonicalJsonV1}（key 字典序、数值归一）；换格式必须换版本号。
 * 服务范围暂由 incidentKey 携带（alertname/service 标签串）——结构化范围字段出现时
 * 升版本号，不原地改语义。
 */
public record InvestigationInputs(UUID incidentId, String incidentKey, int episodeGeneration,
        Instant windowStart, Instant windowEnd) {

    public static final String SCHEMA_VERSION = "investigation-input.v1";

    /** 冻结窗口政策：铸造时刻前推 {@value #RANGE_WINDOW_SECS}s（执行器旧语义同值，锚点改铸时） */
    public static final int RANGE_WINDOW_SECS = 600;

    /** 查询步长（执行器/影子触发同值；进查询参数身份） */
    public static final String STEP = "30s";

    public InvestigationInputs {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(incidentKey, "incidentKey");
        if (incidentKey.isBlank()) {
            throw new IllegalArgumentException("incidentKey 不得为空");
        }
        if (episodeGeneration < 0) {
            throw new IllegalArgumentException("episodeGeneration 不能为负");
        }
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (windowStart.isAfter(windowEnd)) {
            throw new IllegalArgumentException("windowStart 不得晚于 windowEnd");
        }
    }

    /** 铸点冻结：窗口 = [mintedAt-600s, mintedAt]（F14：锚点=铸造时刻，非执行时刻） */
    public static InvestigationInputs freezeAt(Incident incident, Instant mintedAt) {
        return new InvestigationInputs(incident.id(), incident.incidentKey(),
                incident.generation(), mintedAt.minusSeconds(RANGE_WINDOW_SECS), mintedAt);
    }

    /** 调查输入身份 digest（铸点计算一次，随 run 行持久；执行期只透传不重算） */
    public InvestigationInputDigest inputDigest() {
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("range_secs", RANGE_WINDOW_SECS);
        queryParams.put("step", STEP);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", SCHEMA_VERSION);
        canonical.put("incidentId", incidentId.toString());
        canonical.put("incidentKey", incidentKey);
        canonical.put("episodeGeneration", episodeGeneration);
        canonical.put("windowStart", windowStart.toString());
        canonical.put("windowEnd", windowEnd.toString());
        canonical.put("queryParams", queryParams);
        return new InvestigationInputDigest(InternalCanonicalJsonV1.sha256(canonical));
    }

    /** 冻结窗口的 epoch 秒 timeRange 形（工具调用 timeRange 契约形） */
    public String timeRange() {
        return windowStart.getEpochSecond() + "/" + windowEnd.getEpochSecond();
    }
}
