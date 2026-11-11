package com.campuslife.ha;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finding 10：集群模式缺配置必须启动失败；单实例模式仍可启动并明确标注非 HA */
class HaStartupValidatorTest {

    private static HaProperties cluster() {
        HaProperties p = new HaProperties();
        p.setMode("cluster");
        p.setRedisUrl("redis://127.0.0.1:6379");
        p.getStorage().setType("s3");
        p.getStorage().setEndpoint("http://127.0.0.1:9000");
        p.getStorage().setBucket("campus");
        p.getStorage().setAccessKey("ak");
        p.getStorage().setSecretKey("sk");
        return p;
    }

    private static MockEnvironment prod() { return new MockEnvironment().withProperty("spring.profiles.active", "prod"); }

    @Test
    void clusterWithFullConfigStarts() {
        assertThatCode(() -> HaSharedServicesConfiguration.HaStartupValidator.validate(cluster(), prod())).doesNotThrowAnyException();
    }

    @Test
    void clusterWithoutRedisFailsFastAndNamesTheVariable() {
        HaProperties p = cluster();
        p.setRedisUrl("");
        assertThatThrownBy(() -> HaSharedServicesConfiguration.HaStartupValidator.validate(p, prod()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAMPUS_HA_REDIS_URL")
            .hasMessageContaining("不会退回单机实现");
    }

    @Test
    void clusterWithoutObjectStorageFailsFast() {
        HaProperties p = cluster();
        p.getStorage().setType("local");
        p.getStorage().setBucket("");
        assertThatThrownBy(() -> HaSharedServicesConfiguration.HaStartupValidator.validate(p, prod()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAMPUS_HA_STORAGE_TYPE=s3")
            .hasMessageContaining("CAMPUS_HA_STORAGE_BUCKET");
    }

    @Test
    void singleModeStartsInDevAndProdButProdIsMarkedNonHa() {
        HaProperties p = new HaProperties();
        assertThatCode(() -> HaSharedServicesConfiguration.HaStartupValidator.validate(p, new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(() -> HaSharedServicesConfiguration.HaStartupValidator.validate(p, prod())).doesNotThrowAnyException();
    }

    @Test
    void unknownModeIsRejected() {
        HaProperties p = new HaProperties();
        p.setMode("active-active");
        assertThatThrownBy(() -> HaSharedServicesConfiguration.HaStartupValidator.validate(p, prod()))
            .hasMessageContaining("single 或 cluster");
    }

    @Test
    void singleModeDoesNotRequireRedisOrBucket() {
        HaProperties p = new HaProperties();
        p.setRedisUrl("");
        p.getStorage().setType("local");
        assertThatCode(() -> HaSharedServicesConfiguration.HaStartupValidator.validate(p, prod())).doesNotThrowAnyException();
    }
}
