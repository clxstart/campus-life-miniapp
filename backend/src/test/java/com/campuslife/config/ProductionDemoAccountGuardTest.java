package com.campuslife.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 生产库残留已知演示密码时，启动必须失败并列出账号。 */
class ProductionDemoAccountGuardTest {

    @Test
    void noLeakedPasswordRowsStarts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertThatCode(() -> ProductionDemoAccountGuard.validate(jdbc, List.of("$2a$hash"))).doesNotThrowAnyException();
    }

    @Test
    void leakedPasswordRowFailsAndNamesAccount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("id", 1L, "username", "admin", "role", "super")));
        assertThatThrownBy(() -> ProductionDemoAccountGuard.validate(jdbc, List.of("$2a$hash")))
            .hasMessageContaining("生产库仍存在")
            .hasMessageContaining("admin")
            .hasMessageContaining("module-production-demo-account-cleanup.sql");
    }

    @Test
    void emptyHashListDoesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThatCode(() -> ProductionDemoAccountGuard.validate(jdbc, List.of())).doesNotThrowAnyException();
    }

    @Test
    void unrotatedMerchantOrRiderBlocksProductionStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            Map.of("id", 9L, "username", "rider01", "role", "rider"),
            Map.of("id", 12L, "username", "m12345678", "role", "merchant")
        ));
        assertThatThrownBy(() -> ProductionDemoAccountGuard.validateRotatedCredentials(jdbc))
            .hasMessageContaining("未轮换初始凭据")
            .hasMessageContaining("rider01")
            .hasMessageContaining("m12345678");
    }

    @Test
    void rotatedAccountsPass() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        assertThatCode(() -> ProductionDemoAccountGuard.validateRotatedCredentials(jdbc)).doesNotThrowAnyException();
    }
}