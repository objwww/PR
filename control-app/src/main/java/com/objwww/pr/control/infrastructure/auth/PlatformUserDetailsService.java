package com.objwww.pr.control.infrastructure.auth;

import com.objwww.pr.control.auth.domain.PlatformUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * AUTH-1 平台账号登录面（V44 platform_user）：username = platform_user.username，
 * 仅 {@code active=true} 可认证，授权 {@code ROLE_+role} 列（默认 ROLE_OPERATOR——
 * 值班面/只读投影面授权矩阵不变）。
 *
 * <p>查不到/停用一律 UsernameNotFoundException——配合 DaoAuthenticationProvider
 * 默认 hideUserNotFoundExceptions=true 统一 BadCredentials，不泄露"账号存在但
 * 已停用"等细节。
 *
 * <p>v1 限制：停用/改密只挡新登录，已发会话不强制踢出（无会话注册表/吊销面，
 * 后续里程碑再议）。
 */
public class PlatformUserDetailsService implements UserDetailsService {

    private final PlatformUserRepository users;

    public PlatformUserDetailsService(PlatformUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        PlatformUserRepository.AuthUser user = users.findForAuth(username)
                .filter(PlatformUserRepository.AuthUser::active)
                .orElseThrow(() -> new UsernameNotFoundException("platform user not loginable"));
        return User.withUsername(user.username())
                .password(user.passwordHash())
                .roles(user.role())
                .build();
    }
}
