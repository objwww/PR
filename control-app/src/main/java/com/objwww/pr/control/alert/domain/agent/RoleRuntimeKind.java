package com.objwww.pr.control.alert.domain.agent;

/**
 * 角色运行器类型（R7 v2.1 §十一.1/§十一.2）：Profile 声明自己由哪类已部署运行器执行。
 * 运行器目录按本值解析；未部署的 runtimeKind 在编译/准入期显式拒绝（零远端请求，
 * 恢复面 CAPABILITY_UNAVAILABLE），不接受模型上传 Java 类或任意脚本。
 *
 * <p>v2.1：常规新增角色只新增 Profile/输出 schema/工具 allowlist 与路由配置，由同一
 * 运行器执行；需要新协议/工具的角色仍要部署受审查代码——"配置热更新"不意味着
 * Java 实现可热插拔。
 */
public final class RoleRuntimeKind {

    /** 旧确定性三角色的兼容适配运行器（单工具只读证据 Agent 基座；旧路径行为不变硬约束） */
    public static final String DETERMINISTIC_SINGLE_TOOL = "deterministic-single-tool";

    /** 受控有界 LLM 运行器（§十一.1 首个受控运行器 BoundedLlmRoleRunner；主 Agent 载体） */
    public static final String BOUNDED_LLM = "bounded-llm";

    private RoleRuntimeKind() {
    }
}
