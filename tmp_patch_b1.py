# -*- coding: utf-8 -*-
import io

p = r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/PrimaryClaimAdmission.java'
t = io.open(p, encoding='utf-8').read()

# --- 新留痕码
old = """    /** locator 载荷定位解析失败：剥离定位，不影响作用判定本身 */
    public static final String NOTE_LOCATOR_UNRESOLVED = "LOCATOR_UNRESOLVED";"""
new = """    /** RV02/T06：locator 载荷定位解析失败——支持关系不可验证，降 CONTEXT */
    public static final String NOTE_LOCATOR_UNRESOLVED = "LOCATOR_UNRESOLVED";
    /** RV02/T05：UUID 引用读不回证据行（无行/跨 Run）——白名单身份≠载荷可验证 */
    public static final String NOTE_EVIDENCE_ROW_UNRESOLVED = "EVIDENCE_ROW_UNRESOLVED";
    /** RV02/T09：非 UUID artifact 键——身份可证、载荷不可验，只有上下文资格 */
    public static final String NOTE_ARTIFACT_NOT_EVIDENCE_ROW = "ARTIFACT_NOT_EVIDENCE_ROW";
    /** RV02：canonical 载荷不可解析——内容不可验证 */
    public static final String NOTE_PAYLOAD_UNREADABLE = "EVIDENCE_PAYLOAD_UNREADABLE";"""
assert old in t
t = t.replace(old, new)

# --- verdictFor 重构
old = """    /** 单条引用作用授予：未声明→CONTEXT；SUPPORTS 过确定性计数检查与 locator 校验 */
    private static RefVerdict verdictFor(String ref,
            PrimaryDecision.EvidenceRole proposed,
            EvidenceRepository evidence, UUID runId, List<String> claimNotes) {
        if (proposed == null) {
            return new RefVerdict(ref, RefRole.CONTEXT, null, NOTE_SUPPORT_UNDECLARED);
        }
        RefRole role = RefRole.valueOf(proposed.role());
        String locator = proposed.locator();
        String note = "";
        EvidenceEnvelope row = evidenceRow(evidence, runId, ref);
        if (row != null) {
            JsonNode payload = payloadOf(row);
            if (role == RefRole.SUPPORTS) {
                String forced = forcedContextReason(row, payload);
                if (forced != null) {
                    role = RefRole.CONTEXT;
                    note = forced;
                    claimNotes.add(forced);
                }
            }
            if (locator != null && !resolves(payload, locator)) {
                note = note.isEmpty() ? NOTE_LOCATOR_UNRESOLVED
                        : note + "," + NOTE_LOCATOR_UNRESOLVED;
                locator = null;
                claimNotes.add(NOTE_LOCATOR_UNRESOLVED);
            }
        }
        return new RefVerdict(ref, role, locator, note);
    }"""
new = """    /**
     * 单条引用作用授予（RV02：身份、载荷、定位、作用四层分开判断）：
     * <ol>
     *   <li>未声明作用 → CONTEXT（支持关系未确认）；</li>
     *   <li>证据行读不回（UUID 无行/跨 Run）或非 UUID artifact → 只有上下文资格，
     *       白名单身份不自动授予 SUPPORTS/REFUTES；</li>
     *   <li>载荷不可解析 → 不可验证；</li>
     *   <li>SUPPORTS 与 REFUTES 对称受确定性内容检查（全量计数/累计计数器）；</li>
     *   <li>声明了 locator 且定位失败 → 支持关系不可验证，降 CONTEXT
     *      （模型输出错误只影响当前引用，不得打断整案准入）。</li>
     * </ol>
     * 仓未接线（legacy 兼容入口）保持原语义，不新造假校验。
     */
    private static RefVerdict verdictFor(String ref,
            PrimaryDecision.EvidenceRole proposed,
            EvidenceRepository evidence, UUID runId, List<String> claimNotes) {
        if (proposed == null) {
            return new RefVerdict(ref, RefRole.CONTEXT, null, NOTE_SUPPORT_UNDECLARED);
        }
        RefRole role = RefRole.valueOf(proposed.role());
        String locator = proposed.locator();
        EvidenceEnvelope row = evidenceRow(evidence, runId, ref);
        if (row == null) {
            if (evidence != null && runId != null) {
                String why = isUuid(ref)
                        ? NOTE_EVIDENCE_ROW_UNRESOLVED : NOTE_ARTIFACT_NOT_EVIDENCE_ROW;
                claimNotes.add(why);
                return new RefVerdict(ref, RefRole.CONTEXT, null, why);
            }
            return new RefVerdict(ref, role, locator, "");
        }
        JsonNode payload = payloadOf(row);
        if (payload.isMissingNode() || payload.isNull()) {
            claimNotes.add(NOTE_PAYLOAD_UNREADABLE);
            return new RefVerdict(ref, RefRole.CONTEXT, locator, NOTE_PAYLOAD_UNREADABLE);
        }
        String note = "";
        String forced = forcedContextReason(row, payload);
        if (forced != null && role != RefRole.CONTEXT) {
            role = RefRole.CONTEXT;
            note = forced;
            claimNotes.add(forced);
        }
        if (locator != null && !resolves(payload, locator)) {
            role = RefRole.CONTEXT;
            note = note.isEmpty() ? NOTE_LOCATOR_UNRESOLVED
                    : note + "," + NOTE_LOCATOR_UNRESOLVED;
            locator = null;
            claimNotes.add(NOTE_LOCATOR_UNRESOLVED);
        }
        return new RefVerdict(ref, role, locator, note);
    }

    private static boolean isUuid(String ref) {
        try {
            UUID.fromString(ref);
            return true;
        } catch (IllegalArgumentException notUuid) {
            return false;
        }
    }"""
assert old in t
t = t.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(t)
print('verdictFor ok')
