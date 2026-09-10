package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.auth.domain.PlatformUserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AUTH-1 平台账号 PG 实现（V44；control_app 身份）。刻意不带组件注解，
 * 唯一装配点 PersistenceConfig（docker profile）。
 */
public class PostgresPlatformUserRepository implements PlatformUserRepository {

    private final JdbcClient jdbc;

    public PostgresPlatformUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UserView> listUsers() {
        // 写-only 纪律锚：读面显式列清单，永不含 password_hash
        return jdbc.sql("""
                select username, display_name, role, active, created_at
                from platform_user order by username
                """).query((rs, i) -> view(rs)).list();
    }

    @Override
    public UserView insertUser(String username, String displayName, String passwordHash,
                               String role) {
        jdbc.sql("""
                insert into platform_user(username, display_name, password_hash, role,
                    active, created_at, updated_at)
                values (:username, :display, :hash, :role, true, now(), now())
                """).param("username", username)
                .param("display", displayName == null ? "" : displayName)
                .param("hash", passwordHash).param("role", role).update();
        return new UserView(username, displayName == null ? "" : displayName, role, true,
                Instant.now());
    }

    @Override
    public boolean updatePassword(String username, String passwordHash) {
        return jdbc.sql("""
                update platform_user set password_hash = :hash, updated_at = now()
                where username = :username
                """).param("hash", passwordHash).param("username", username).update() == 1;
    }

    @Override
    public boolean updateActive(String username, boolean active) {
        return jdbc.sql("""
                update platform_user set active = :active, updated_at = now()
                where username = :username
                """).param("active", active).param("username", username).update() == 1;
    }

    @Override
    public Optional<AuthUser> findForAuth(String username) {
        return jdbc.sql("""
                select username, password_hash, role, active from platform_user
                where username = :username
                """).param("username", username)
                .query((rs, i) -> new AuthUser(rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("role"),
                        rs.getBoolean("active")))
                .optional();
    }

    private static UserView view(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new UserView(rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getBoolean("active"),
                created == null ? null : created.toInstant());
    }
}
