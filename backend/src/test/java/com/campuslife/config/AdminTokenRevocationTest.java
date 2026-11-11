package com.campuslife.config;

import com.campuslife.mapper.AdminMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Finding 03：停用/重新启用/改权限后旧令牌失效，数据库故障时拒绝而非放行 */
class AdminTokenRevocationTest {
    private final AdminMapper mapper = mock(AdminMapper.class);
    private final AdminJwtUtil jwt = new AdminJwtUtil("test-secret-0123456789-0123456789-0123456789", 3600);
    private final AdminTokenGuard guard = new AdminTokenGuard(mapper);
    private final AdminInterceptor admin = new AdminInterceptor(jwt, guard);
    private final RiderInterceptor rider = new RiderInterceptor(jwt, guard);

    private static Map<String, Object> state(String role, Long schoolId, int status, long tokenVersion) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 3L); m.put("role", role); m.put("schoolId", schoolId); m.put("shopId", null);
        m.put("status", status); m.put("tokenVersion", tokenVersion);
        return m;
    }

    private static MockHttpServletRequest call(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private int adminStatus(String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = admin.preHandle(call("/api/admin/orders", token), response, null);
        return pass ? 200 : response.getStatus();
    }

    @Test
    void activeAdminPasses() throws Exception {
        when(mapper.tokenState(3L)).thenReturn(state("school", 9L, 1, 0));
        assertThat(adminStatus(jwt.issue(3L, "school", 9L, null, "a", 0))).isEqualTo(200);
    }

    @Test
    void disabledAdminTokenIsRejectedImmediately() throws Exception {
        String token = jwt.issue(3L, "school", 9L, null, "a", 0);
        when(mapper.tokenState(3L)).thenReturn(state("school", 9L, 0, 1));
        assertThat(adminStatus(token)).isEqualTo(401);
    }

    @Test
    void reEnabledAccountDoesNotReviveOldToken() throws Exception {
        String token = jwt.issue(3L, "school", 9L, null, "a", 0);
        // 停用 +1、启用 +1 → 版本 2，停用前的令牌版本 0
        when(mapper.tokenState(3L)).thenReturn(state("school", 9L, 1, 2));
        assertThat(adminStatus(token)).isEqualTo(401);
        // 重新登录拿到的新令牌可用
        assertThat(adminStatus(jwt.issue(3L, "school", 9L, null, "a", 2))).isEqualTo(200);
    }

    @Test
    void legacyTokenWithoutVersionClaimFollowsVersionZero() throws Exception {
        String legacy = jwt.issue(3L, "school", 9L, null, "a"); // 迁移前签发、无 tv 语义 → 按 0
        when(mapper.tokenState(3L)).thenReturn(state("school", 9L, 1, 0));
        assertThat(adminStatus(legacy)).isEqualTo(200);
        when(mapper.tokenState(3L)).thenReturn(state("school", 9L, 1, 1));
        assertThat(adminStatus(legacy)).isEqualTo(401);
    }

    @Test
    void roleOrSchoolChangeInvalidatesToken() throws Exception {
        String token = jwt.issue(3L, "super", null, null, "a", 0);
        when(mapper.tokenState(3L)).thenReturn(state("school", 9L, 1, 0));
        assertThat(adminStatus(token)).isEqualTo(401);
        String school9 = jwt.issue(3L, "school", 9L, null, "a", 0);
        when(mapper.tokenState(3L)).thenReturn(state("school", 10L, 1, 0));
        assertThat(adminStatus(school9)).isEqualTo(401);
    }

    @Test
    void databaseFailureFailsClosed() throws Exception {
        when(mapper.tokenState(3L)).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(adminStatus(jwt.issue(3L, "school", 9L, null, "a", 0))).isEqualTo(503);
    }

    @Test
    void expiredTokenRejectedBeforeDatabase() throws Exception {
        AdminJwtUtil expired = new AdminJwtUtil("test-secret-0123456789-0123456789-0123456789", -10);
        assertThat(adminStatus(expired.issue(3L, "school", 9L, null, "a", 0))).isEqualTo(401);
        verifyNoInteractions(mapper);
    }

    @Test
    void riderPasswordResetRevokesRiderToken() throws Exception {
        String token = jwt.issue(5L, "rider", 9L, null, "r", 0);
        Map<String, Object> s = state("rider", 9L, 1, 1); s.put("id", 5L);
        when(mapper.tokenState(5L)).thenReturn(s);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(rider.preHandle(call("/api/rider/pool", token), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void merchantWithTemporaryPasswordCanReachChangePasswordButNotBusinessApi() throws Exception {
        String token = jwt.issue(7L, "merchant", null, 12L, "m", 0);
        Map<String, Object> s = state("merchant", null, 1, 0);
        s.put("id", 7L); s.put("shopId", 12L); s.put("mustChangePassword", 1);
        when(mapper.tokenState(7L)).thenReturn(s);

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertThat(admin.preHandle(call("/api/merchant/home", token), blocked, null)).isFalse();
        assertThat(blocked.getStatus()).isEqualTo(403);

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        assertThat(admin.preHandle(call("/api/admin/change-password", token), allowed, null)).isTrue();
    }

    @Test
    void riderWithTemporaryPasswordCanReachChangePasswordButNotBusinessApi() throws Exception {
        String token = jwt.issue(8L, "rider", 9L, null, "r", 0);
        Map<String, Object> s = state("rider", 9L, 1, 0);
        s.put("id", 8L); s.put("mustChangePassword", 1);
        when(mapper.tokenState(8L)).thenReturn(s);

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertThat(rider.preHandle(call("/api/rider/pool", token), blocked, null)).isFalse();
        assertThat(blocked.getStatus()).isEqualTo(403);

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        assertThat(admin.preHandle(call("/api/admin/change-password", token), allowed, null)).isTrue();
    }
}
