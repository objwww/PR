package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.shared.IllegalTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 枚举状态机的通用迁移表（迁移矩阵唯一权威；UT-A01~A04 反射穷举的断言对象）。
 *
 * <p>所有告警域状态机共用本件，禁止各机自散落 if-else——矩阵即契约，
 * 测试用反射穷举所有 (from, to) 组合对齐矩阵。
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
        public Builder<S> allow(S from, S to, @SuppressWarnings("unchecked") S... more) {
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
