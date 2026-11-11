package com.campuslife.service;

import java.security.SecureRandom;

/**
 * 账号一次性初始密码生成器。
 *
 * 不派生自手机号、用户名或时间等可预测信息；排除容易混淆的 I/O/0/1，
 * 保证人工转抄时不会因为字形看错而反复登录失败。
 */
public final class TemporaryPasswords {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LENGTH = 12;

    private TemporaryPasswords() {}

    public static String generate() {
        char[] value = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new String(value);
    }
}