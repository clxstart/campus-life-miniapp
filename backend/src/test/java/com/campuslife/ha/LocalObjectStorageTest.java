package com.campuslife.ha;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finding 10：本机存储适配器保持原有路径/URL 语义，且拒绝路径穿越 */
class LocalObjectStorageTest {

    private static LocalObjectStorage storage(Path dir) {
        return new LocalObjectStorage(dir.toString(), "/uploads", "/private", "http://127.0.0.1:8080");
    }

    private static ByteArrayInputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void publicFileRoundTripAndUrlShape(@TempDir Path dir) throws Exception {
        LocalObjectStorage storage = storage(dir);
        ObjectStorage.Stored stored = storage.put("abc.png", bytes("img"), 3, "image/png");
        assertThat(stored.url()).isEqualTo("http://127.0.0.1:8080/uploads/abc.png");
        assertThat(stored.path()).isEqualTo("/uploads/abc.png");
        assertThat(storage.publicUrl("abc.png")).isEqualTo(stored.url());
        assertThat(new String(storage.readPublic("abc.png"), StandardCharsets.UTF_8)).isEqualTo("img");
    }

    @Test
    void privateFileIsNotExposedThroughPublicDir(@TempDir Path dir) throws Exception {
        LocalObjectStorage storage = storage(dir);
        storage.putPrivate("lic.jpg", bytes("secret"), 6, "image/jpeg");
        assertThat(new String(storage.readPrivate("lic.jpg"), StandardCharsets.UTF_8)).isEqualTo("secret");
        assertThat(dir.resolve("private").resolve("lic.jpg")).exists();
        // 公开读取不应拿到私有文件
        assertThatThrownBy(() -> storage.readPublic("lic.jpg")).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void traversalInNameIsStripped(@TempDir Path dir) throws Exception {
        LocalObjectStorage storage = storage(dir);
        assertThatThrownBy(() -> storage.put("../../etc/passwd", bytes("x"), 1, "image/png"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalObjectStorage.safeName("..%2f..%2fx.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalObjectStorage.safeName("..")).isInstanceOf(IllegalArgumentException.class);
        assertThat(LocalObjectStorage.safeName("a1b2c3.png")).isEqualTo("a1b2c3.png");
    }

    @Test
    void localRootIsReportedSoDiskChecksStillWork(@TempDir Path dir) {
        assertThat(storage(dir).localRoot()).isNotNull();
    }

    @Test
    void healthCheckPassesOnWritableDirectory(@TempDir Path dir) {
        assertThat(java.util.Arrays.asList(dir.toFile().list())).isNotNull();
        try { storage(dir).healthCheck(); } catch (Exception e) { throw new AssertionError(e); }
    }
}
