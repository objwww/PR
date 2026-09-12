package com.objwww.pr.control.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4/G7 价目注入绑定面（部署契约钉住）：模型名含连字符（qwen3-max-preview）时
 * map 键不经 relaxed binding 改写——SPRING_APPLICATION_JSON（展平为 dotted key 的
 * MapPropertySource，同 SpringApplicationJsonEnvironmentPostProcessor 形态）键按字面
 * 保留，绑定成立；环境变量下划线形态（APP_MODEL_PRICE_QWEN3_MAX_PREVIEW_*）无法
 * 无歧义还原连字符 map 键——本测试同时钉住"为什么部署注入必须走 SPRING_APPLICATION_JSON"。
 * 刊例价数值本身由运维按实际服务方核定后注入（红线：不填 0、不编价格）。
 */
class ModelPriceBindingTest {

    /** SPRING_APPLICATION_JSON 形态：JSON 展平后的 dotted-key Map（JsonJsonParser 载体） */
    @Test
    void springApplicationJson形态_连字符模型键按字面绑定() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.model.price.qwen3-max-preview.input-micros-per-1k", 2500,
                "app.model.price.qwen3-max-preview.output-micros-per-1k", 10000,
                "app.model.price.qwen3-max-preview.pricing-version", "bailian-qwen3-max-preview-2026-09",
                "app.model.price.qwen3-max-preview.currency", "CNY"));

        M3ModelGatewayConfig.ModelGatewayProperties props = new Binder(source)
                .bind("app.model", Bindable.of(M3ModelGatewayConfig.ModelGatewayProperties.class))
                .get();

        var entry = props.getPrice().get("qwen3-max-preview");
        assertThat(entry).as("连字符键原样进 map（不被 relaxed 改写）").isNotNull();
        assertThat(entry.getInputMicrosPer1k()).isEqualTo(2500);
        assertThat(entry.getOutputMicrosPer1k()).isEqualTo(10000);
        assertThat(entry.getPricingVersion()).isEqualTo("bailian-qwen3-max-preview-2026-09");
        assertThat(entry.getCurrency()).isEqualTo("CNY");
    }

    @Test
    void 环境变量下划线形态_无法还原连字符map键() {
        // 真实 env 注入面：SystemEnvironmentPropertySource 命名转换后，
        // "QWEN3_MAX_PREVIEW" 无法区分 "qwen3.max.preview" / "qwen3-max-preview"——
        // 绑定结果拿不到按连字符命名的条目（部署面因此禁止 env 形态注入价目）
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of(
                "APP_MODEL_PRICE_QWEN3_MAX_PREVIEW_INPUT_MICROS_PER_1K", "2500")));

        M3ModelGatewayConfig.ModelGatewayProperties props = Binder.get(env)
                .bind("app.model", Bindable.of(M3ModelGatewayConfig.ModelGatewayProperties.class))
                // 真实部署形态：无 yaml 兜底时 env 形态连 app.model 整体都绑不出——
                // 即便有其他 app.model 键，price map 也拿不到连字符条目
                .orElseGet(M3ModelGatewayConfig.ModelGatewayProperties::new);

        assertThat(props.getPrice().get("qwen3-max-preview"))
                .as("env 下划线形态绑定不出连字符 map 键——SPRING_APPLICATION_JSON 是唯一注入面")
                .isNull();
        assertThat(props.getPrice()).as("价目表整体为空（未注入任何条目）").isEmpty();
    }

    /** MapPropertySource（SPRING_APPLICATION_JSON 落 Environment 的载体）同面成立 */
    @Test
    void mapPropertySource载体_同SpringApplicationJsonPostProcessor展平形态() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("springApplicationJson",
                Map.of("app.model.price.glm-5.input-micros-per-1k", 4000,
                        "app.model.price.glm-5.output-micros-per-1k", 18000)));

        M3ModelGatewayConfig.ModelGatewayProperties props = Binder.get(env)
                .bind("app.model", Bindable.of(M3ModelGatewayConfig.ModelGatewayProperties.class))
                .get();

        assertThat(props.getPrice()).containsKey("glm-5");
    }
}
