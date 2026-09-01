package com.objwww.pr.publisher.domain.handler;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * UT-24（M2 方案 §11/L1，§4.4）：ProbeResult 封闭类型四值——sealed 反射断言 +
 * "found=true 且无 digest" 非法组合的类型层面证明。
 */
class ProbeResultTest {

    @Test
    void isSealedWithExactlyFourLegalShapes() {
        assertThat(ProbeResult.class.isSealed()).isTrue();
        assertThat(Arrays.stream(ProbeResult.class.getPermittedSubclasses())
                .map(Class::getSimpleName).toList())
                .containsExactlyInAnyOrder("FoundNoContent", "FoundWithContent", "NotFound", "Unknown");
        assertThatNullPointerException()
                .isThrownBy(() -> new ProbeResult.FoundWithContent("1", null, null));
    }

    @Test
    void foundWithoutDigestExistsOnlyAsExplicitNoContentShape() {
        // 类型层面证明："found 且携带 digest" 只有 FoundWithContent，其 contentDigest 分量
        // 类型为 Digest 且紧凑构造器 NPE 拒 null（上条已钉）——found=true+无 digest 的非法
        // 组合无法被构造；其余三形态在类型上根本没有 digest 分量。
        assertThat(Arrays.stream(ProbeResult.FoundWithContent.class.getRecordComponents()))
                .anySatisfy(component -> {
                    assertThat(component.getName()).isEqualTo("contentDigest");
                    assertThat(component.getType()).isEqualTo(com.objwww.pr.shared.Digest.class);
                });
        for (Class<?> shape : Arrays.asList(ProbeResult.FoundNoContent.class,
                ProbeResult.NotFound.class, ProbeResult.Unknown.class)) {
            assertThat(Arrays.stream(shape.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getType).toList())
                    .as("%s 不得携带 digest 分量", shape.getSimpleName())
                    .doesNotContain(com.objwww.pr.shared.Digest.class);
        }
        // sealed 四值全部 final（record 隐式 final，此处显式钉死）：
        // 不存在第五种可扩展形态让非法组合从缝隙里进来
        assertThat(ProbeResult.class.getPermittedSubclasses())
                .allMatch(type -> java.lang.reflect.Modifier.isFinal(type.getModifiers()));
    }
}
