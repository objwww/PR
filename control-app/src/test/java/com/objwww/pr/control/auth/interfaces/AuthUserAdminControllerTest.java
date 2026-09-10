package com.objwww.pr.control.auth.interfaces;

import com.objwww.pr.control.auth.domain.PlatformUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-1 平台账号管理面锚：创建（服务端 BCrypt、响应不含 hash）、重名 409、
 * 短密码 400、重置密码、启停、自停 400 防自锁、列表读面零 hash 泄露。
 * 授权矩阵（401/403/404 链级）在 SecurityConfigTest.authUsersFaceRoleMatrix。
 */
class AuthUserAdminControllerTest {

    private final PlatformUserRepository users = mock(PlatformUserRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new AuthUserAdminController(users, new BCryptPasswordEncoder())).build();
    }

    private static UsernamePasswordAuthenticationToken principal(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a");
    }

    @Test
    @DisplayName("创建：服务端 BCrypt 入库（入参是哈希非明文），响应体不含 password")
    void createHashesServerSideAndNeverEchoesPassword() throws Exception {
        when(users.insertUser(eq("alice"), eq("Alice"), anyString(), eq("OPERATOR")))
                .thenReturn(new PlatformUserRepository.UserView("alice", "Alice", "OPERATOR",
                        true, Instant.parse("2026-09-10T00:00:00Z")));

        mvc.perform(post("/api/auth/users").contentType("application/json")
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\","
                                + "\"password\":\"alice-pass-88\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.role").value("OPERATOR"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("password"));
        org.mockito.ArgumentCaptor<String> hashCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(users).insertUser(eq("alice"), eq("Alice"), hashCaptor.capture(), eq("OPERATOR"));
        assertThat(hashCaptor.getValue()).startsWith("$2").doesNotContain("alice-pass-88");
    }

    @Test
    @DisplayName("创建：重名 → 409；缺/短密码 → 400 且不落库；怪 role → 400")
    void createValidatesAndMapsDuplicateTo409() throws Exception {
        when(users.insertUser(eq("dup"), any(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("pk"));
        mvc.perform(post("/api/auth/users").contentType("application/json")
                        .content("{\"username\":\"dup\",\"password\":\"dup-pass-1234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("username 已存在"));

        mvc.perform(post("/api/auth/users").contentType("application/json")
                        .content("{\"username\":\"x\",\"password\":\"short7\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/users").contentType("application/json")
                        .content("{\"username\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/users").contentType("application/json")
                        .content("{\"username\":\" \",\"password\":\"long-enough-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/users").contentType("application/json")
                        .content("{\"username\":\"x\",\"password\":\"long-enough-1\","
                                + "\"role\":\"bad role!\"}"))
                .andExpect(status().isBadRequest());
        verify(users, never()).insertUser(eq("x"), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("重置密码：200（哈希入库）/账号不在 404/短密码 400")
    void resetPasswordMatrix() throws Exception {
        when(users.updatePassword(eq("alice"), anyString())).thenReturn(true);
        mvc.perform(post("/api/auth/users/alice/password").contentType("application/json")
                        .content("{\"password\":\"new-pass-999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        org.mockito.ArgumentCaptor<String> hashCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(users).updatePassword(eq("alice"), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).startsWith("$2").doesNotContain("new-pass-999");

        mvc.perform(post("/api/auth/users/alice/password").contentType("application/json")
                        .content("{\"password\":\"short7\"}"))
                .andExpect(status().isBadRequest());

        when(users.updatePassword(eq("ghost"), anyString())).thenReturn(false);
        mvc.perform(post("/api/auth/users/ghost/password").contentType("application/json")
                        .content("{\"password\":\"ghost-pass-1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("启停：停用他人 200；账号不在 404；停用当前登录者本人 → 400 防自锁；启用本人不受限")
    void setActiveGuardsSelfLockout() throws Exception {
        when(users.updateActive(eq("bob"), anyBoolean())).thenReturn(true);
        mvc.perform(post("/api/auth/users/bob/active").principal(principal("alice"))
                        .contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isOk());
        verify(users).updateActive("bob", false);

        when(users.updateActive(eq("ghost"), anyBoolean())).thenReturn(false);
        mvc.perform(post("/api/auth/users/ghost/active").principal(principal("alice"))
                        .contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isNotFound());

        // 自停拒绝（不落库）；自启放行
        mvc.perform(post("/api/auth/users/alice/active").principal(principal("alice"))
                        .contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不能停用当前登录者本人账号（防自锁）"));
        verify(users, never()).updateActive(eq("alice"), eq(false));

        when(users.updateActive(eq("alice"), eq(true))).thenReturn(true);
        mvc.perform(post("/api/auth/users/alice/active").principal(principal("alice"))
                        .contentType("application/json").content("{\"active\":true}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/users/bob/active").principal(principal("alice"))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("列表读面：字段为 username/displayName/role/active/createdAt，绝不含 password_hash")
    void listNeverLeaksPasswordHash() throws Exception {
        when(users.listUsers()).thenReturn(List.of(
                new PlatformUserRepository.UserView("alice", "Alice", "OPERATOR", true,
                        Instant.parse("2026-09-10T00:00:00Z"))));
        mvc.perform(get("/api/auth/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].displayName").value("Alice"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("password"));
    }
}
