package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.release.application.EngineComparisonRecorder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引擎对照结论记录器装配面钉住（M6-07 回迁形态）：docker profile 常驻的
 * {@link AlertFlowConfig} 不再承载该 bean——BA-56 上收的原因（docker profile
 * 影子 worker）已随 Holmes 退场退役，公共面摘除；唯一消费者回到
 * am4-shadow-trigger 专属 profile（一次性入口）。双面各钉一针。
 *
 * @author wanghua
 * @date 2026-09-09
 */
class EngineComparisonRecorderWiringTest {

    @Test
    void alertFlowConfigNeverAssemblesTheRecorderAfterRetirement() {
        List<Method> beans = Arrays.stream(AlertFlowConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(
                        org.springframework.context.annotation.Bean.class))
                .filter(m -> m.getReturnType().equals(EngineComparisonRecorder.class))
                .collect(Collectors.toList());
        assertThat(beans).isEmpty();
    }

    @Test
    void am4ShadowTriggerProfileOwnsTheRecorder() {
        List<Method> beans = Arrays.stream(Am4ShadowTriggerConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(
                        org.springframework.context.annotation.Bean.class))
                .filter(m -> m.getReturnType().equals(EngineComparisonRecorder.class))
                .collect(Collectors.toList());
        assertThat(beans).hasSize(1);
    }
}
