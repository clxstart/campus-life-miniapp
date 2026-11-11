package com.campuslife.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 一次性密码不能从手机号等可预测信息派生，且必须满足登录/改密的长度限制。 */
class TemporaryPasswordsTest {
    @Test
    void generatedPasswordIsRandomAndLongEnough() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String password = TemporaryPasswords.generate();
            assertThat(password).hasSize(12).matches("[A-Za-z2-9]+");
            seen.add(password);
        }
        assertThat(seen).hasSize(200);
    }
}