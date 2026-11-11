package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.mapper.RiderMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Finding 01：骑手订单响应不得包含完整手机号或其别名 */
class RiderServicePhoneTest {
    private final RiderMapper mapper = mock(RiderMapper.class);
    private final RiderService service = new RiderService(mapper, null, null);

    private static Map<String, Object> row() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("shopName", "一食堂");
        row.put("contactPhone", "13812345678");
        // 模拟历史 SQL 或未来误加的别名列：白名单必须把它挡在响应外
        row.put("contactPhoneFull", "13812345678");
        row.put("phoneRaw", "13812345678");
        return row;
    }

    @Test
    void poolOnlyReturnsMaskedPhone() {
        when(mapper.pool(9L)).thenReturn(List.of(row()));
        Map<String, Object> out = service.pool(9L).get(0);
        assertThat(out).doesNotContainKeys("contactPhoneFull", "phoneRaw");
        assertThat(out.get("contactPhone")).isEqualTo("138****5678");
        assertThat(out.values()).doesNotContain("13812345678");
    }

    @Test
    void mineOnlyReturnsMaskedPhone() {
        when(mapper.mine(5L)).thenReturn(List.of(row()));
        Map<String, Object> out = service.mine(5L).get(0);
        assertThat(out).doesNotContainKeys("contactPhoneFull", "phoneRaw");
        assertThat(out.values()).doesNotContain("13812345678");
    }

    @Test
    void revealOnlyForOwnActiveOrder() {
        when(mapper.activeContactPhone(1L, 5L)).thenReturn("13812345678");
        assertThat(service.revealPhone(1L, 5L)).isEqualTo("13812345678");

        // 非本人、未分配或已完成订单：SQL 查不到 → 拒绝
        when(mapper.activeContactPhone(2L, 5L)).thenReturn(null);
        assertThatThrownBy(() -> service.revealPhone(2L, 5L)).isInstanceOf(ForbiddenException.class);
    }
}
