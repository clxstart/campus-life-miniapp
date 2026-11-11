package com.campuslife.service;

import com.campuslife.config.JwtUtil;
import com.campuslife.entity.User;
import com.campuslife.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 登录模式：生产漏配微信时必须 fail-closed，绝不能把所有 code 解析成 dev-1。 */
class AuthServiceLoginModeTest {

    private static MockEnvironment environment(String profile) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) env.setActiveProfiles(profile);
        return env;
    }

    private static AuthService service(String profile, boolean devLoginEnabled, String appid, String secret) {
        UserMapper mapper = mock(UserMapper.class);
        JwtUtil jwt = new JwtUtil("0123456789abcdef0123456789abcdef", 3600);
        AuthService service = new AuthService(mapper, jwt, mock(PointsService.class), mock(TierService.class), environment(profile));
        ReflectionTestUtils.setField(service, "devLoginConfigured", devLoginEnabled);
        ReflectionTestUtils.setField(service, "devLoginAnyUser", false);
        ReflectionTestUtils.setField(service, "appid", appid);
        ReflectionTestUtils.setField(service, "secret", secret);
        return service;
    }

    @Test
    void devLoginRequiresExplicitSwitchInDev() {
        AuthService service = service(null, false, "", "");
        assertThat(service.devLoginEnabled()).isFalse();
    }

    @Test
    void devLoginWorksOnlyWhenExplicitlyEnabledAndWechatMissing() {
        AuthService service = service(null, true, "", "");
        assertThat(service.devLoginEnabled()).isTrue();
    }

    @Test
    void devLoginIsForcedOffInProductionEvenIfConfigured() {
        AuthService service = service("prod", true, "", "");
        assertThat(service.devLoginEnabled()).isFalse();
    }

    @Test
    void configuredWechatDisablesDevLogin() {
        AuthService service = service(null, true, "wx1234567890", "secret");
        assertThat(service.devLoginEnabled()).isFalse();
        assertThat(service.wechatConfigured()).isTrue();
    }

    @Test
    void touristAppIdDoesNotCountAsConfigured() {
        AuthService service = service(null, true, "touristappid", "secret");
        assertThat(service.wechatConfigured()).isFalse();
        assertThat(service.devLoginEnabled()).isTrue();
    }

    @Test
    void productionWithoutWechatParametersRejectsWechatLoginInsteadOfFallingBackToDevOne() {
        AuthService service = service("prod", true, "", "");
        assertThatThrownBy(() -> service.login("any-code"))
            .isInstanceOf(com.campuslife.config.ServiceUnavailableException.class)
            .hasMessageContaining("微信登录未配置");
    }

    @Test
    void productionDevLoginIsRejected() {
        AuthService service = service("prod", true, "", "");
        assertThatThrownBy(() -> service.devLogin(1L))
            .hasMessageContaining("生产环境不允许开发登录");
    }

    @Test
    void devLoginCreatesTokenForExistingDemoUserOnly() {
        UserMapper mapper = mock(UserMapper.class);
        User user = new User();
        user.setId(1L);
        user.setNickname("演示同学");
        when(mapper.selectById(1L)).thenReturn(user);
        AuthService service = new AuthService(mapper, new JwtUtil("0123456789abcdef0123456789abcdef", 3600),
            mock(PointsService.class), mock(TierService.class), environment(null));
        ReflectionTestUtils.setField(service, "devLoginConfigured", true);
        ReflectionTestUtils.setField(service, "devLoginAnyUser", false);
        ReflectionTestUtils.setField(service, "appid", "");
        ReflectionTestUtils.setField(service, "secret", "");
        assertThat(service.devLogin(1L)).containsEntry("userId", 1L);
    }
}