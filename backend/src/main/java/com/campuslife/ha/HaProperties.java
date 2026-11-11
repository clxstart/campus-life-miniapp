package com.campuslife.ha;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 部署形态配置（finding-10）。
 *
 * campus.ha.mode:
 *   single  —— 单实例（开发默认）：上传落本机目录、限流/群聊房间/任务锁都在进程内。明确是非 HA 部署。
 *   cluster —— 多实例：必须提供共享 Redis（限流、群聊跨节点广播与在线人数）和 S3 兼容对象存储（上传），
 *              周期任务用共享 MySQL 租约锁协调。缺任何一项启动即失败，绝不静默降级回单机实现。
 * 生产 profile 不给默认值，必须显式声明 single 或 cluster。
 */
@Data
@ConfigurationProperties(prefix = "campus.ha")
public class HaProperties {
    /** single | cluster */
    private String mode = "single";
    /** 节点标识（日志、任务锁持有者、在线人数分片）；默认 主机名-进程号 */
    private String nodeId = "";
    /** 集群模式必填：redis://[:password@]host:port[/db] 或 rediss://… */
    private String redisUrl = "";
    /** Redis 命令超时（毫秒） */
    private long redisTimeoutMs = 2000;
    /**
     * readiness 探测 Redis 的上界（毫秒）：看门狗线程最多等这么久就判 DOWN。
     * 必须显著小于负载均衡/编排的探针超时（通常 1~3s），否则 Redis 挂死时探针会跟着一起卡住。
     */
    private long redisHealthTimeoutMs = 500;
    /**
     * 共享限流不可用（Redis 故障）时的策略：false（默认）= 拒绝请求返回 429，宁可短暂不可用也不放开限流；
     * true = 放行并告警。两种选择都会计数告警，不会悄悄退回进程内计数。
     */
    private boolean rateLimitFailOpen = false;

    private Storage storage = new Storage();

    @Data
    public static class Storage {
        /** local | s3；cluster 模式必须是 s3 */
        private String type = "local";
        /** S3 兼容服务地址，例如 https://s3.ap-east-1.amazonaws.com、https://oss-cn-hangzhou.aliyuncs.com、http://minio:9000 */
        private String endpoint = "";
        private String region = "";
        private String bucket = "";
        private String accessKey = "";
        private String secretKey = "";
        /** 公开文件访问基址（可挂 CDN 或自定义域名）；留空则用 {endpoint}/{bucket}/ */
        private String publicBaseUrl = "";
    }

    public boolean isCluster() { return "cluster".equalsIgnoreCase(mode); }
    public boolean isS3() { return "s3".equalsIgnoreCase(storage.getType()); }
}
