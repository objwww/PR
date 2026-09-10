package com.objwww.pr.control.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AUTH-1：平台账号仓储端口（V44 platform_user）。登录权限是独立的平台账号
 * 概念，不挂值班花名册——duty_member 纯排班语义。
 *
 * <p>写-only 密码纪律：password_hash 的唯一合法读出口是
 * {@link #findForAuth}（只服务认证比对）；管理面读视图 {@link UserView}
 * 不含哈希列，任何 HTTP 读接口不得出参。
 */
public interface PlatformUserRepository {

    /** 管理面读视图（无 password_hash——写-only 列） */
    record UserView(String username, String displayName, String role, boolean active,
                    Instant createdAt) {
    }

    /** 登录认证最小行（含哈希与角色；消费者只有 UserDetailsService） */
    record AuthUser(String username, String passwordHash, String role, boolean active) {
    }

    List<UserView> listUsers();

    /** 创建账号（重名撞主键 → DataIntegrityViolationException，由调用方映射 409） */
    UserView insertUser(String username, String displayName, String passwordHash, String role);

    /** 重置密码（BCrypt 哈希入库；账号不存在 = false）。改密下次登录生效（v1 不踢已发会话） */
    boolean updatePassword(String username, String passwordHash);

    /** 启用/停用（账号不存在 = false）。停用即无法新登录（v1 不踢已发会话） */
    boolean updateActive(String username, boolean active);

    /** 按登录名取认证行；查不到 = Optional.empty()（调用方统一判负，不区分原因） */
    Optional<AuthUser> findForAuth(String username);
}
