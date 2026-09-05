package com.objwww.pr.arena.domain.statemachine;

import com.objwww.pr.shared.IllegalTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 枚举状态机的通用迁移表（迁移矩阵唯一权威；反射穷举测试的断言对象）。
 *
 * <p>与 control-app 告警域的 TransitionTable 同构（刻意复制而非跨模块共享——
 * 靶场进程不依赖控制面模块；两份矩阵各自封闭，由各自的穷举测试看护）。
 * 所有 arena 状态机共用本件，禁止散落 if-else——矩阵即契约。
 */
public final class TransitionTable<S extends Enum<S>> {

    private final Class<S> type;
    private final Map<S, Set<S>> allowed;

    private TransitionTable(Class<S> type, Map<S, Set<S>> allowed) {
        this.type = type;
        this.allowed = allowed;
    }

    public static <S extends Enum<S>> Builder<S> forEnum(Class<S> type) {
        return new Builder<>(type);
    }

    /** 该迁移是否被矩阵允许 */
    public boolean allowed(S from, S to) {
        Set<S> targets = allowed.get(from);
        return targets != null && targets.contains(to);
    }

    /** 不允许即抛 IllegalTransitionException（仓储层迁移前统一走此门） */
    public void requireTransition(S from, S to) {
        if (!allowed(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    /** from 的全部合法目标（穷举测试用；未配置的 from = 空集即终态） */
    public Set<S> targets(S from) {
        Set<S> targets = allowed.get(from);
        return targets == null ? Set.of() : Set.copyOf(targets);
    }

    public Class<S> type() {
        return type;
    }

    public static final class Builder<S extends Enum<S>> {
        private final Class<S> type;
        private final Map<S, Set<S>> allowed;

        private Builder(Class<S> type) {
            this.type = type;
            this.allowed = new EnumMap<>(type);
        }

        /** from 的目标集从空起步，allow 逐条累加；未配置的 from（终态）= 空目标集 */
        @SafeVarargs
        public final Builder<S> allow(S from, S to, S... more) {
            allowed.computeIfAbsent(from, k -> EnumSet.noneOf(type)).add(to);
            for (S t : more) {
                allowed.get(from).add(t);
            }
            return this;
        }

        public TransitionTable<S> build() {
            return new TransitionTable<>(type, allowed);
        }
    }
}
