package com.objwww.pr.control.drill.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.eval.application.ScenarioDriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DR-03 注入回执成形（worker 落 WORKER_NOTE 的载荷；§7.4「每次动作前先持久化
 * 稳定身份」）：稳定身份 = 有效实例 scenarioId + generation + actionDigest
 * + drillId 锚。GT 字段（expectedRootCause 等）不进入回执——本面只承载执行
 * 身份（§7.3 GT 纪律）。序化失败 = 编程错误（Map 序化无 IO），快速失败。
 */
final class DrillInjectionReceipt {

    /** 静态共享实例（配置后即线程安全；DrillWorker.PARAMS_JSON 同式） */
    private static final ObjectMapper JSON = new ObjectMapper();

    private DrillInjectionReceipt() {
    }

    /**
     * @param trafficNote 非空 = 激活后流量/等待阶段失败（W1 ActivationException
     *                    路径）如实记录——故障已激活不丢现场，也不冒充完整注入成功
     */
    static String json(DrillJob job, String driver,
                       ScenarioDriver.ActivationReceipt receipt, String trafficNote) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("drillId", job.id().toString());
        map.put("driver", driver);
        map.put("scenarioId", receipt.scenarioId());
        map.put("actionDigest", receipt.actionDigest());
        map.put("generation", receipt.generation());
        map.put("expectedAlertIdentity", receipt.expectedAlertIdentity());
        if (trafficNote != null) {
            map.put("trafficNote", trafficNote);
        }
        try {
            return JSON.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("注入回执序化失败（不应发生）", e);
        }
    }
}
