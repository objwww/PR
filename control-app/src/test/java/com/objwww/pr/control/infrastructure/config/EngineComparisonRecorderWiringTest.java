package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.release.application.EngineComparisonRecorder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-56 装配面钉住（本地回归绊线）：引擎对照结论记录器必须由 docker profile
 * 常驻的 {@link AlertFlowConfig} 装配，且不得回挂 am4-shadow-trigger 专属
 * profile（195 真启动实证的缺口形态——本地默认 profile smoke 不加载 docker
 * 装配，只能靠本钉住 + 195 真启动双面守）。
 *
 * @author wanghua
 * @date 2026-09-09
 */
class EngineComparisonRecorderWiringTest {

    @Test
    void alertFlowConfigAssemblesRecorderOnTheCommonFace() {
        List<Method> beans = Arrays.stream(AlertFlowConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(
                        org.springframework.context.annotation.Bean.class))
                .filter(m -> m.getReturnType().equals(EngineComparisonRecorder.class))
                .collect(Collectors.toList());
        assertThat(beans).hasSize(1);
    }

    @Test
    void am4ShadowTriggerProfileNeverOwnsTheRecorder() {
        List<Method> beans = Arrays.stream(Am4ShadowTriggerConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(
                        org.springframework.context.annotation.Bean.class))
                .filter(m -> m.getReturnType().equals(EngineComparisonRecorder.class))
                .collect(Collectors.toList());
        assertThat(beans).isEmpty();
    }
}
