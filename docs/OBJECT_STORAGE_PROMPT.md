# 对象存储迁移 · GPT 提示词

> 工程目录：`E:\_verify_completed\campus-life-miniapp`
> 目标：把图片存储从「服务器本地磁盘」换成「对象存储」，支持 CDN
> 技术选型：**阿里云 OSS**（可切换到腾讯云 COS / MinIO）

---

=== 提示词开始 ===

## 一、你的任务

把项目现在的**本地文件存储**改成**对象存储**。原因：

1. 服务器磁盘会满（学生发帖发图，一天几百张）
2. 本地图片没法用 CDN，加载慢
3. 服务器迁移或重装，图片全丢

**要做到：**

- 上传的图片直接进 OSS，不落本地磁盘
- 数据库里存的 URL 能直接访问
- 能通过配置开关切回本地（开发环境用）
- 已有的历史图片能迁移过去

## 二、项目背景

- 后端：Spring Boot 3.2.5 + MyBatis-Plus 3.5.6 + MySQL 8，包名 `com.campuslife`
- 数据库：`campus_life`，账号 `root`
- 统一返回 `{code, msg, data}`
- 小程序端图片必须走 **HTTPS**

## 三、动手前必须先读这些文件

1. `backend/.../service/UploadService.java` —— **核心，要改的就是这个**
2. `backend/.../controller/UploadController.java` —— 上传接口
3. `backend/.../service/UploadRateLimiter.java` —— 限流（不要动）
4. `backend/src/main/resources/application.yml` —— 配置
5. `backend/.../config/GlobalExceptionHandler.java` —— 异常处理
6. `backend/src/main/resources/db/` —— 迁移脚本风格

## 四、现有结构（重要）

### 4.1 现在的配置

```yaml
campus:
  upload:
    dir: uploads                        # 本地目录
    public-prefix: /uploads             # 公开文件路径前缀
    private-prefix: /private            # 私有文件路径前缀
    base-url: http://127.0.0.1:8080     # 返回给前端的基址
```

### 4.2 现在的 `UploadService` 关键方法

```java
public Path root()                     // 返回 uploads 目录的绝对路径
public Path privateRoot()              // 返回 uploads/private 目录
public Map<String,Object> store(MultipartFile file)          // 存公开文件
public Map<String,Object> storePrivate(MultipartFile file)   // 存私有文件
public Map<String,Object> cleanOrphans(boolean dryRun, int olderThanDays)  // 清理孤儿文件
private static String detectImageType(MultipartFile file)    // 魔数校验（不要动）
```

**用 `java.nio.file.Files` 直接写磁盘。**

### 4.3 三种路径要区分（关键）

| 前缀 | 含义 | 是否要迁到 OSS |
|---|---|---|
| `/assets/` | 小程序内置静态资源 | ❌ **不迁**，这些是打包在小程序里的 |
| `/uploads/` | 用户上传的公开文件（帖子图、商品图） | ✅ **要迁** |
| `/private/` | 私有文件（学生证、营业执照） | ⚠️ **要迁，但不能放公开 bucket** |

**当前数据库里**：`/assets/` 8 条，`/uploads/` 0 条，`/private/` 0 条。

（现在没有真实上传文件，正好是迁移的最佳时机。）

## 五、要做什么

### 5.1 抽象存储接口（最重要的设计）

**不要直接在 `UploadService` 里加 OSS 代码。** 先抽一个接口：

```java
public interface StorageService {

    /**
     * 存公开文件，返回可公开访问的 URL
     * @return { url: "https://cdn.xxx.com/uploads/a.jpg", path: "/uploads/a.jpg" }
     */
    Map<String, Object> storePublic(MultipartFile file, String ext);

    /**
     * 存私有文件，返回不可公开访问的标识
     * @return { url: "", path: "/private/a.jpg" }
     */
    Map<String, Object> storePrivate(MultipartFile file, String ext);

    /**
     * 生成私有文件的临时访问地址（签名 URL，默认 1 小时有效）
     */
    String signPrivateUrl(String path, long expireSeconds);

    /**
     * 删除文件
     */
    void delete(String path);

    /**
     * 当前存储类型，用于日志和健康检查
     */
    String type();
}
```

**两个实现：**

- `LocalStorageService` —— 现有逻辑，`@ConditionalOnProperty(name="campus.storage.type", havingValue="local", matchIfMissing=true)`
- `OssStorageService` —— 新的，`@ConditionalOnProperty(name="campus.storage.type", havingValue="oss")`

**这样 `UploadService` 和 `UploadController` 几乎不用改**，只是把 `Files.write()` 换成 `storage.storePublic()`。

### 5.2 引入 OSS SDK

在 `backend/pom.xml` 加：

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.17.4</version>
</dependency>
```

**如果要支持多家（阿里云/腾讯云/MinIO），用 S3 兼容协议更好：**

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.25.0</version>
</dependency>
```

阿里云 OSS、腾讯云 COS、MinIO 都支持 S3 兼容接口，一套代码通吃。

**建议用 S3 SDK**，这样以后换云厂商不用改代码。

### 5.3 配置项

在 `application.yml` 加：

```yaml
campus:
  storage:
    type: local          # local | oss
  oss:
    endpoint: ''         # 如 https://oss-cn-beijing.aliyuncs.com
    region: ''           # 如 cn-beijing
    access-key-id: ''
    access-key-secret: ''
    bucket: ''           # 公开读的 bucket
    private-bucket: ''   # 私有读的 bucket（可和上面同一个）
    public-domain: ''    # 公开访问域名，如 https://cdn.example.com
    path-prefix: 'uploads'  # 对象 key 前缀
    # 私有文件的签名有效期（秒）
    sign-expire: 3600
```

**安全要求：**

- `access-key-id` / `access-key-secret` **不要写死在代码里**
- 用环境变量覆盖：`${OSS_ACCESS_KEY_ID:}`
- 生产环境用 RAM 子账号，只给这个 bucket 的读写权限，不要用主账号 AK

```yaml
access-key-id: ${OSS_ACCESS_KEY_ID:}
access-key-secret: ${OSS_ACCESS_KEY_SECRET:}
```

### 5.4 OSS 实现要点

**对象 key 的规则（重要，要保证 URL 可预测）：**

```
{path-prefix}/{yyyy}/{MM}/{dd}/{uuid}.{ext}

例：uploads/2026/10/19/a1b2c3d4-e5f6-7890.jpg
```

**为什么按日期分目录**：OSS 单目录文件过多会影响性能，按日期分是标准做法。

**公开文件返回的 URL：**

```java
// 如果配了 CDN 域名
https://cdn.example.com/uploads/2026/10/19/xxx.jpg

// 没配 CDN，用 OSS 默认域名
https://bucket.oss-cn-beijing.aliyuncs.com/uploads/2026/10/19/xxx.jpg
```

**数据库存什么（关键决策）：**

**存相对路径 `/uploads/2026/10/19/xxx.jpg`，不存完整 URL。**

理由：
- 换 CDN 域名不用改数据库
- 开发环境（本地）和生产环境（OSS）可以共用同一份数据

前端取图时，由后端拼完整地址（或者在返回 DTO 时拼）。

**但要注意**：如果小程序端直接用数据库里的相对路径拼 `base-url`，那 `base-url` 在 OSS 模式下要指向 CDN 域名。

**建议做法**：后端在返回 DTO 时统一把相对路径转成完整 URL。

写一个工具方法：

```java
public String toPublicUrl(String path) {
    if (path == null || path.isEmpty()) return path;
    if (path.startsWith("http://") || path.startsWith("https://")) return path;  // 已经是完整地址
    if (path.startsWith("/assets/")) return baseUrl + path;   // 小程序内置资源，走原地址
    return publicDomain + path;                                // OSS 资源
}
```

### 5.5 私有文件的特殊处理

`/private/` 存的是学生证、营业执照这类敏感文件，**绝对不能放公开读的 bucket**。

三个方案，选一个：

**方案 A：单独的私有 bucket（推荐）**

- 建一个 `private-bucket`，权限设为「私有」
- 上传后用**签名 URL** 访问，默认 1 小时过期
- 管理端看学生证时，后端现签一个临时 URL 返回

```java
public String signPrivateUrl(String path, long expireSeconds) {
    Date expiration = new Date(System.currentTimeMillis() + expireSeconds * 1000);
    GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
        privateBucket, path, HttpMethod.GET);
    request.setExpiration(expiration);
    return ossClient.generatePresignedUrl(request).toString();
}
```

**方案 B：私有文件继续存本地**

- 公开文件走 OSS，私有文件走本地磁盘
- 简单，但私有文件会占服务器磁盘（不过这类文件很少，可以接受）

**方案 C：同一个 bucket + 目录级权限**

- 阿里云 OSS 不支持目录级权限，做不到
- ❌ 不推荐

**建议用方案 A。** 如果嫌麻烦，方案 B 也能接受。

### 5.6 历史文件迁移

写一个**一次性的迁移工具**（可以是 CommandLineRunner，也可以是单独的 Controller 接口）：

```
1. 扫描本地 uploads/ 目录下的所有文件
2. 逐个上传到 OSS
3. 记录映射关系（本地路径 → OSS 路径）
4. 更新数据库里的图片字段
```

**要更新的表和字段：**

| 表 | 字段 |
|---|---|
| `post` | `image` |
| `goods` | `image` |
| `campus_activity` | `poster` |
| `circle` | `cover` |
| `shop` | `cover` |
| `shop_goods` | `image` |
| `idol_contestant` | `photo` |
| `school` | `logo` |
| `user` | `avatar` |
| `banner` | `image` |
| `merchant_apply` | `license_image`、`shop_image` |

**注意：只迁移 `/uploads/` 开头的路径，`/assets/` 不要动。**

迁移要**幂等**：重复跑不会重复上传（先查数据库里有没有已经是 OSS 路径的）。

### 5.7 上传接口改造

`UploadController` 的接口签名**保持不变**：

```
POST /api/upload
POST /api/admin/upload
POST /api/admin/upload/private
```

只改内部实现：

```java
// 改之前
public Map<String,Object> store(MultipartFile file) {
    // ... Files.write(...)
    return Map.of("url", baseUrl + publicPrefix + "/" + name);
}

// 改之后
public Map<String,Object> store(MultipartFile file) {
    String ext = detectImageType(file);      // 魔数校验，保留
    if (ext == null) throw new IllegalArgumentException("只支持 JPG/PNG/GIF/WEBP");
    if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("图片不能超过 5MB");
    return storageService.storePublic(file, ext);
}
```

**`detectImageType()` 的魔数校验必须保留**——这是防止伪装文件上传的安全措施。

### 5.8 清理孤儿文件

现有的 `cleanOrphans(dryRun, olderThanDays)` 要改成查 OSS：

- 列出 bucket 里 `uploads/` 前缀下的所有对象
- 和数据库里引用的路径比对
- 找出 N 天前的孤儿文件

**注意：OSS 的 ListObjects 有分页，要循环拉完。**

这个功能可以**先不做**，等文件多了再说。但要留个 TODO。

### 5.9 上传失败的处理

OSS 可能因为网络、限流、密钥过期而失败。

**策略：**

```java
try {
    return storageService.storePublic(file, ext);
} catch (Exception e) {
    log.error("OSS 上传失败", e);
    // 不要静默降级到本地，会让人以为成功了
    throw new IllegalStateException("图片上传失败，请稍后重试");
}
```

**不要做「OSS 失败就存本地」的自动降级**——那样会出现一部分图在 OSS、一部分在本地的混乱状态。

宁可报错让用户重试。

## 六、验收标准

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 后端能编译 | Maven BUILD SUCCESS |
| 2 | 本地模式仍可用 | `type=local` 时，上传还是存本地，功能不变 |
| 3 | OSS 模式可用 | `type=oss` 时，上传的图能直接在浏览器打开 |
| 4 | **URL 是 HTTPS** | 返回的地址必须 `https://` 开头 |
| 5 | 数据库存相对路径 | 存的不是完整 URL |
| 6 | 按日期分目录 | 对象 key 形如 `uploads/2026/10/19/xxx.jpg` |
| 7 | 魔数校验保留 | 上传 .txt 改名为 .jpg，被拒绝 |
| 8 | 大小限制保留 | 上传 6MB 图被拒绝 |
| 9 | 限流保留 | 1 分钟内传 21 张，第 21 张返回 429 |
| 10 | **私有文件不可直接访问** | 复制 `/private/xxx` 的直链，浏览器打开返回 403 |
| 11 | 私有文件能签名访问 | 调接口拿签名 URL，能打开，1 小时后失效 |
| 12 | 历史迁移 | 跑迁移工具，`/uploads/` 的图全部变 OSS |
| 13 | `/assets/` 不受影响 | 小程序内置图仍正常显示 |
| 14 | 迁移幂等 | 连跑两次，第二次跳过已迁移的 |

## 七、不要做的事

- **不要直接在 `UploadService` 里写 OSS 代码**（必须抽 `StorageService` 接口）
- **不要把 AK/SK 写进代码或 `application.yml`**（用环境变量）
- **不要把私有文件放公开 bucket**
- **不要在 OSS 失败时静默降级到本地**（会导致数据分散）
- **不要删掉 `detectImageType()` 魔数校验**
- **不要动 `UploadRateLimiter`**
- **不要改上传接口的 URL 和返回结构**（前端在用）
- **不要迁移 `/assets/` 目录**（那是小程序内置资源）
- 不要用主账号 AK（用 RAM 子账号，最小权限）

## 八、交付顺序

1. 抽 `StorageService` 接口 + `LocalStorageService` 实现（先把现有逻辑搬过去，保证功能不变）
2. 验证：`type=local` 时一切正常，跑一遍测试
3. 引入 OSS SDK + `OssStorageService` 实现
4. 配置项 + 环境变量
5. 验证：`type=oss` 时上传能成功
6. 私有文件签名 URL
7. 历史文件迁移工具
8. 孤儿文件清理（可选，可以延后）

**第 1-2 步是重构，不改变任何行为。做完先跑测试确认没破坏原有功能，再往下做。**

=== 提示词结束 ===

---

## 附：给你的说明

### 为什么先抽接口

如果直接让 GPT 在 `UploadService` 里加 OSS 代码，结果是：

- 代码里到处 `if (isOss) {...} else {...}`
- 想切回本地很麻烦
- 测试没法跑（CI 里没有 OSS 密钥）

抽了接口之后：

- 开发环境用 `local`，不依赖网络
- 生产环境用 `oss`
- 换腾讯云只需要加一个实现类

### 为什么数据库存相对路径

如果存完整 URL（`https://cdn.example.com/uploads/xxx.jpg`），会遇到：

- 换 CDN 域名 → 要改几十万条数据
- 开发环境看到的图打不开（因为存的是生产域名）

存 `/uploads/2026/10/19/xxx.jpg`，展示时再拼域名，最灵活。

### 阿里云还是腾讯云

看你的服务器在哪：

- 服务器在阿里云 → 用阿里云 OSS（内网传输免费、快）
- 服务器在腾讯云 → 用腾讯云 COS
- 服务器自建 → 用 MinIO（免费，但没 CDN）

**三家都支持 S3 兼容接口，所以用 S3 SDK 写一次，三家通用。**

### 成本估算

阿里云 OSS 标准存储：约 0.12 元/GB/月。

假设：
- 每天 500 张图，每张 300KB → 150MB/天 → 4.5GB/月
- 存储费：约 0.54 元/月
- 流量费（假设每天 5000 次访问，每次 300KB）→ 1.5GB/天 → 45GB/月
- 流量费：约 11 元/月

**合计约 12 元/月**，很便宜。

CDN 另算，但校园场景访问量不大，可以先用 OSS 直连，有需要再挂 CDN。

### 上线前必须做

1. **OSS bucket 要开 HTTPS**（小程序只接受 HTTPS）
2. **微信公众平台配置 `downloadFile` 合法域名**（加 OSS 或 CDN 域名）
3. **RAM 子账号最小权限**（只给这个 bucket 的 `PutObject`/`GetObject`/`DeleteObject`）

### 工作量

| 部分 | 时间 |
|---|---|
| 抽接口 + 本地实现（重构） | 0.5 天 |
| OSS 实现 + 配置 | 0.5 天 |
| 私有文件签名 | 0.3 天 |
| 历史迁移工具 | 0.5 天 |
| 联调测试 | 0.5 天 |
| **合计** | **约 2.3 天** |

比之前估的 1-2 天略多，主要因为要写迁移工具和私有文件签名。
