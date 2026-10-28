package com.campuslife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campuslife.dto.CreateOrderRequest;
import com.campuslife.entity.Shop;
import com.campuslife.entity.ShopGoods;
import com.campuslife.entity.User;
import com.campuslife.entity.UserOrder;
import com.campuslife.mapper.ModuleMapper;
import com.campuslife.mapper.ShopGoodsMapper;
import com.campuslife.mapper.ShopMapper;
import com.campuslife.mapper.UserMapper;
import com.campuslife.mapper.UserAddressMapper;
import com.campuslife.mapper.UserOrderMapper;
import com.campuslife.dto.AuditResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizService {
    private final ShopMapper shopMapper;
    private final ShopGoodsMapper goodsMapper;
    private final UserOrderMapper orderMapper;
    private final UserMapper userMapper;
    private final UserAddressMapper userAddressMapper;
    private final ModuleMapper moduleMapper;
    // 下单成功后累加店铺真实订单数，供首页「今日推荐」按真实热度排序
    private final com.campuslife.mapper.RecommendMapper recommendMapper;
    private final ObjectMapper json;
    private final AuditService auditService;
    private final FeatureFlags featureFlags;
    private static final BigDecimal PACKING_FEE = new BigDecimal("2.00");

    // 外卖店铺：按校隔离（各校商家不同），schoolScope=all 时看全部学校
    public List<Map<String,Object>> shops(String category, String schoolScope, Long explicitSchoolId, Long userId) {
        String c = category == null ? "" : category.trim();
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, userId);
        // 上限保护：店铺列表不分页，但必须有硬上限，避免店铺多了首屏返回全量
        List<Shop> shops = shopMapper.selectList(new LambdaQueryWrapper<Shop>()
            .eq(Shop::getStatus, 1)
            .eq(!c.isEmpty() && !"全部".equals(c), Shop::getCategory, c)
            .eq(schoolId != null, Shop::getSchoolId, schoolId)
            .orderByAsc(Shop::getId)
            .last("LIMIT 200"));
        return shops.stream().map(this::publicShop).toList();
    }

    private Map<String,Object> publicShop(Shop shop) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", shop.getId()); row.put("name", shop.getName()); row.put("cover", shop.getCover());
        row.put("category", shop.getCategory()); row.put("rating", shop.getRating()); row.put("monthlySales", shop.getMonthlySales());
        row.put("minPrice", shop.getMinPrice()); row.put("deliveryFee", shop.getDeliveryFee()); row.put("deliveryTime", shop.getDeliveryTime());
        row.put("discount", shop.getDiscount()); row.put("notice", shop.getNotice()); row.put("schoolId", shop.getSchoolId());
        row.put("contactPhone", shop.getContactPhone()); row.put("address", shop.getAddress()); row.put("openTime", shop.getOpenTime());
        row.put("closeTime", shop.getCloseTime()); row.put("status", shop.getStatus()); row.put("isOpen", orderable(shop));
        return row;
    }

    private static boolean orderable(Shop shop) {
        if (!Integer.valueOf(1).equals(shop.getStatus()) || !Integer.valueOf(1).equals(shop.getIsOpen())) return false;
        try {
            LocalTime now = LocalTime.now();
            LocalTime open = LocalTime.parse(shop.getOpenTime());
            LocalTime close = LocalTime.parse(shop.getCloseTime());
            if (open.equals(close)) return true;
            return open.isBefore(close) ? !now.isBefore(open) && now.isBefore(close) : !now.isBefore(open) || now.isBefore(close);
        } catch (Exception error) {
            return false;
        }
    }

    private Shop requireShop(Long id) {
        Shop s = shopMapper.selectById(id);
        if (s == null || !Integer.valueOf(1).equals(s.getStatus())) throw new IllegalArgumentException("店铺不存在或已休息");
        return s;
    }

    public List<Map<String, Object>> menu(Long id) {
        requireShop(id);
        List<ShopGoods> list = goodsMapper.selectList(new LambdaQueryWrapper<ShopGoods>().eq(ShopGoods::getShopId, id).eq(ShopGoods::getStatus, 1).orderByAsc(ShopGoods::getSortNum).orderByAsc(ShopGoods::getId));
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (ShopGoods g : list) {
            groups.computeIfAbsent(g.getCategory(), k -> new ArrayList<>()).add(Map.of("id", g.getId(), "name", g.getName(), "price", g.getPrice(), "monthlySales", g.getMonthlySales(), "image", g.getImage(), "status", g.getStatus()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        groups.forEach((category, goods) -> out.add(Map.of("category", category, "goods", goods)));
        return out;
    }

    // 二手列表：按校隔离（异地无法面交），schoolScope=all 时看全部学校
    public List<Map<String, Object>> goods(Integer mode, String category, String schoolScope, Long explicitSchoolId, Long userId) {
        int safeMode = mode != null && mode == 2 ? 2 : 1;
        String safeCategory = category == null ? "" : category.trim();
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, userId);
        return moduleMapper.selectGoods(safeMode, safeCategory, schoolId).stream().map(this::goodListView).toList();
    }

    /** 分页版二手列表。过滤条件与 goods() 一致，只是多了 LIMIT/OFFSET 与总数。 */
    public Map<String, Object> goodsPaged(Integer mode, String category, String schoolScope, Long explicitSchoolId,
                                          Long userId, int page, int size) {
        int safeMode = mode != null && mode == 2 ? 2 : 1;
        String safeCategory = category == null ? "" : category.trim();
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, userId);
        int safeSize = Math.min(50, Math.max(1, size));
        int safePage = Math.max(1, page);
        List<Map<String, Object>> rows = moduleMapper.selectGoodsPaged(safeMode, safeCategory, schoolId, safeSize, (safePage - 1) * safeSize)
            .stream().map(this::goodListView).toList();
        long total = moduleMapper.countGoods(safeMode, safeCategory, schoolId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows); out.put("total", total); out.put("page", safePage); out.put("size", safeSize);
        return out;
    }

    // 用户学校归属；无归属返回 null（此时不加学校过滤，避免新用户看到空列表）
    private Long userSchoolId(Long userId) {
        if (userId == null || userId < 1) return null;
        User u = userMapper.selectById(userId);
        return u == null ? null : u.getSchoolId();
    }

    /**
     * 判断图片地址是不是服务端能提供的：
     *   - /uploads/xxx      上传接口返回的路径
     *   - /assets/xxx       内置静态图
     *   - http(s)://xxx     外部图床
     * 注意：微信小程序的本地临时路径有两种形态，必须显式拒绝：
     *   - wxfile://tmp_xxx
     *   - http://tmp/xxx     （看起来像 http，其实是本地文件）
     */
    private static boolean isServableImage(String url) {
        if (url == null) return false;
        String value = url.trim();
        if (value.isEmpty()) return false;
        if (value.startsWith("wxfile://")) return false;
        if (value.startsWith("http://tmp/") || value.startsWith("https://tmp/")) return false;
        if (value.startsWith("http://") || value.startsWith("https://")) return true;
        return value.startsWith("/uploads/") || value.startsWith("/assets/");
    }

    /**
     * 学校范围解析（三档优先级）：
     *   1. 显式 schoolId —— 小程序里用户选中的学校，最高优先级
     *   2. schoolScope=all —— 看全部学校
     *   3. 兜底 —— 用户自己所属学校（保持原有行为）
     *
     * 为什么要显式 schoolId：小程序可以切学校，而「用户所属学校」是账号属性，
     * 两者不是一回事。切换学校时如果不传 schoolId，切了也等于没切。
     */
    private Long resolveSchoolId(String schoolScope, Long explicitSchoolId, Long userId) {
        if (explicitSchoolId != null && explicitSchoolId > 0) return explicitSchoolId;
        if ("all".equals(schoolScope)) return null;
        return userSchoolId(userId);
    }

    // 删除自己发布的商品
    @Transactional
    public void deleteGood(Long id, Long userId) {
        if (id == null || id < 1) throw new IllegalArgumentException("商品参数无效");
        if (userId == null || userId < 1) throw new IllegalArgumentException("用户参数无效");
        if (moduleMapper.deleteGood(id, userId) != 1) throw new IllegalArgumentException("商品不存在或无权删除");
    }

    public Map<String, Object> good(Long id) {
        Map<String, Object> row = moduleMapper.selectGood(id);
        if (row == null) throw new IllegalArgumentException("物品不存在或已下架");
        return goodView(row);
    }

    @Transactional
    public Map<String, Object> publishGood(Map<String, Object> body) {
        Long userId = requiredLong(body.get("userId"), "用户不存在");
        if (userMapper.selectById(userId) == null) throw new IllegalArgumentException("用户不存在");
        String title = text(body.get("title")).trim();
        if (title.length() < 1 || title.length() > 64) throw new IllegalArgumentException("标题长度须为1至64字");
        BigDecimal price;
        try {
            price = new BigDecimal(text(body.get("price")).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("价格格式不正确");
        }
        if (price.signum() < 0 || price.compareTo(new BigDecimal("99999")) > 0) throw new IllegalArgumentException("价格须为0至99999");
        String description = text(body.get("description")).trim();
        if (description.length() > 200) throw new IllegalArgumentException("描述不能超过200字");
        String conditionLevel = textOr(body.get("conditionLevel"), "九成新");
        String category = textOr(body.get("category"), "其他");
        if (conditionLevel.length() > 8) throw new IllegalArgumentException("成色格式不正确");
        if (category.length() > 16) throw new IllegalArgumentException("分类格式不正确");
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> images = new ArrayList<>();
        Object imagesRaw = body.get("images");
        if (imagesRaw instanceof List) {
            for (Object entry : (List<?>) imagesRaw) {
                String img = text(entry).trim();
                if (img.isEmpty()) continue;
                if (img.length() > 255) throw new IllegalArgumentException("图片地址过长");
                images.add(img);
            }
        }
        if (images.size() > 6) throw new IllegalArgumentException("图片最多上传6张");
        if (images.isEmpty()) throw new IllegalArgumentException("请至少上传一张图片");
        // 图片必须是服务端可访问的地址。小程序若把 wxfile://tmp_xxx 这类本地临时路径
        // 直接提交上来，存进库后图片永远打不开 —— 这里直接拒绝，挡住脏数据。
        for (String img : images) {
            if (!isServableImage(img)) {
                throw new IllegalArgumentException("图片未上传成功，请重新选择图片");
            }
        }
        AuditResult textAudit = auditService.checkText(title + "\n" + description, "goods", userId);
        auditService.rejectWithLog(textAudit, "goods", userId, title + "\n" + description);
        String imagesJson;
        try {
            imagesJson = json.writeValueAsString(images);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("图片信息保存失败", e);
        }
        params.put("userId", userId);
        params.put("mode", 1);
        params.put("title", title);
        params.put("price", price);
        params.put("conditionLevel", conditionLevel);
        params.put("category", category);
        params.put("image", images.get(0));
        params.put("images", imagesJson);
        params.put("description", description);
        String auditStatus = auditService.initialStatus(textAudit, true);
        params.put("auditStatus", auditStatus);
        params.put("auditReason", textAudit.reason());
        params.put("auditAt", "passed".equals(auditStatus) ? LocalDateTime.now() : null);
        params.put("status", "passed".equals(auditStatus) ? 1 : 0);
        // 商品归属发布者的学校：不写会导致新商品变成无主数据（旧数据靠 SQL 回填过）
        params.put("schoolId", userSchoolId(userId));
        moduleMapper.insertGood(params);
        Long goodsId = longValue(params.get("id"));
        auditService.logTextResult("goods", goodsId, userId, title + "\n" + description, textAudit);
        auditService.scheduleImagesAfterCommit(images, "goods", goodsId, userId);
        return Map.of("id", params.get("id"));
    }

    // 兼职列表：按校隔离（校内兼职），schoolScope=all 时看全部学校
    /**
     * 分页版兼职列表。
     *   - page/size 由调用方给，服务端做上限保护（size ≤ 50）；
     *   - 过滤条件与不分页版完全一致，避免两套逻辑走偏。
     */
    public Map<String, Object> jobsPaged(String settle, String keyword, String schoolScope, Long explicitSchoolId,
                                        Long userId, int page, int size) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() > 64) throw new IllegalArgumentException("搜索词不能超过64字");
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, userId);
        int safeSize = Math.min(50, Math.max(1, size));
        int safePage = Math.max(1, page);
        String settleValue = settle == null ? "" : settle.trim();
        String like = kw.isEmpty() ? "" : "%" + escapeLike(kw) + "%";
        List<Map<String, Object>> rows = moduleMapper.selectJobsPaged(settleValue, like, schoolId, safeSize, (safePage - 1) * safeSize);
        long total = moduleMapper.countJobs(settleValue, like, schoolId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows); out.put("total", total); out.put("page", safePage); out.put("size", safeSize);
        return out;
    }

    public List<Map<String, Object>> jobs(String settle, String keyword, String schoolScope, Long explicitSchoolId, Long userId) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() > 64) throw new IllegalArgumentException("搜索词不能超过64字");
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, userId);
        return moduleMapper.selectJobs(
            settle == null ? "" : settle.trim(),
            kw.isEmpty() ? "" : "%" + escapeLike(kw) + "%",
            schoolId);
    }

    // LIKE 通配符转义：让用户输入的 % 和 _ 按字面匹配，而不是当通配符
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // 发布兼职：让「发布」页的「兼职信息」选项成为真实流程
    @Transactional
    public Map<String, Object> publishJob(Map<String, Object> body) {
        Long userId = requiredLong(body.get("userId"), "用户不存在");
        if (userMapper.selectById(userId) == null) throw new IllegalArgumentException("用户不存在");
        String title = text(body.get("title")).trim();
        if (title.isEmpty() || title.length() > 64) throw new IllegalArgumentException("标题长度须为1至64字");
        int daySalary;
        try {
            daySalary = Integer.parseInt(text(body.get("daySalary")).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("日薪格式不正确");
        }
        if (daySalary < 0 || daySalary > 100000) throw new IllegalArgumentException("日薪须为0至100000");
        String settle = textOr(body.get("settle"), "日结");
        if (settle.length() > 8) throw new IllegalArgumentException("结算方式格式不正确");
        String location = textOr(body.get("location"), "").trim();
        if (location.length() > 64) throw new IllegalArgumentException("地点不能超过64字");
        String timeDesc = textOr(body.get("timeDesc"), "").trim();
        if (timeDesc.length() > 64) throw new IllegalArgumentException("时间说明不能超过64字");
        String description = text(body.get("description")).trim();
        if (description.length() > 200) throw new IllegalArgumentException("描述不能超过200字");
        AuditResult textAudit = auditService.checkText(title + "\n" + description, "job", userId);
        auditService.rejectWithLog(textAudit, "job", userId, title + "\n" + description);
        Map<String, Object> params = new LinkedHashMap<>();
        String auditStatus = auditService.initialStatus(textAudit, false);
        params.put("userId", userId); params.put("title", title); params.put("daySalary", daySalary);
        params.put("settle", settle); params.put("location", location);
        params.put("timeDesc", timeDesc); params.put("description", description);
        params.put("auditStatus", auditStatus); params.put("auditReason", textAudit.reason());
        params.put("auditAt", "passed".equals(auditStatus) ? LocalDateTime.now() : null);
        params.put("status", "passed".equals(auditStatus) ? "招聘中" : "待审核");
        // 岗位归属发布者学校：不写会导致新岗位在本校视图消失
        params.put("schoolId", userSchoolId(userId));
        moduleMapper.insertJob(params);
        auditService.logTextResult("job", longValue(params.get("id")), userId, title + "\n" + description, textAudit);
        return Map.of("id", params.get("id"));
    }

    // 发布活动：让「发布」页的「活动组队」选项成为真实流程
    @Transactional
    public Map<String, Object> publishActivity(Map<String, Object> body) {
        Long userId = requiredLong(body.get("userId"), "用户不存在");
        if (userMapper.selectById(userId) == null) throw new IllegalArgumentException("用户不存在");
        String title = text(body.get("title")).trim();
        if (title.isEmpty() || title.length() > 64) throw new IllegalArgumentException("标题长度须为1至64字");
        String poster = textOr(body.get("poster"), "").trim();
        if (poster.length() > 255) throw new IllegalArgumentException("图片地址过长");
        // 同商品：拒绝本地临时路径，避免存进库后图片打不开
        if (!poster.isEmpty() && !isServableImage(poster)) {
            throw new IllegalArgumentException("图片未上传成功，请重新选择图片");
        }
        String timeDesc = textOr(body.get("timeDesc"), "").trim();
        if (timeDesc.length() > 32) throw new IllegalArgumentException("时间说明不能超过32字");
        String location = textOr(body.get("location"), "").trim();
        if (location.length() > 64) throw new IllegalArgumentException("地点不能超过64字");
        String description = text(body.get("description")).trim();
        if (description.length() > 200) throw new IllegalArgumentException("描述不能超过200字");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId); params.put("title", title); params.put("poster", poster);
        params.put("timeDesc", timeDesc); params.put("location", location); params.put("description", description);
        // 活动归属发布者学校：不写会导致新活动在本校视图消失
        params.put("schoolId", userSchoolId(userId));
        // 报名模式：默认一键报名；发布时也可以直接选「材料+审核」
        String enrollMode = "material".equals(text(body.get("enrollMode"))) ? "material" : "simple";
        String enrollHint = text(body.get("enrollHint")).trim();
        if (enrollHint.length() > 200) throw new IllegalArgumentException("报名须知不能超过200字");
        params.put("enrollMode", enrollMode);
        params.put("enrollHint", enrollHint);
        params.put("enrollMaxFiles", Math.min(9, Math.max(1, intOr(body.get("enrollMaxFiles"), 3))));
        moduleMapper.insertActivity(params);
        return Map.of("id", params.get("id"));
    }

    // 我发布的内容计数，供个人中心展示
    public Map<String, Object> myPublishCount(Long userId) {
        Long uid = (userId == null || userId < 1) ? 1L : userId;
        Map<String, Object> raw = moduleMapper.countMyPublish(uid);
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw != null) for (Map.Entry<String, Object> entry : raw.entrySet()) out.put(entry.getKey(), number(entry.getValue()));
        return out;
    }

    // 校园活动：按校隔离（各校活动不同），schoolScope=all 时看全部学校
    public List<Map<String, Object>> activities(Long userId, String schoolScope, Long explicitSchoolId) {
        Long safeUserId = userId == null ? 1L : userId;
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, safeUserId);
        List<Map<String, Object>> rows = moduleMapper.selectActivities(safeUserId, schoolId);
        for (Map<String, Object> row : rows) row.put("enrolledByMe", number(row.get("enrolledByMe")) > 0);
        return rows;
    }

    /** 分页版活动列表。过滤条件与 activities() 一致。 */
    public Map<String, Object> activitiesPaged(Long userId, String schoolScope, Long explicitSchoolId, int page, int size) {
        Long safeUserId = userId == null ? 1L : userId;
        Long schoolId = resolveSchoolId(schoolScope, explicitSchoolId, safeUserId);
        int safeSize = Math.min(50, Math.max(1, size));
        int safePage = Math.max(1, page);
        List<Map<String, Object>> rows = moduleMapper.selectActivitiesPaged(safeUserId, schoolId, safeSize, (safePage - 1) * safeSize);
        for (Map<String, Object> row : rows) row.put("enrolledByMe", number(row.get("enrolledByMe")) > 0);
        long total = moduleMapper.countActivities(schoolId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows); out.put("total", total); out.put("page", safePage); out.put("size", safeSize);
        return out;
    }

    /**
     * 报名活动。按活动自己的报名模式分流：
     *   simple   → 一键报名，直接计入报名数（原行为）
     *   material → 必须带表单字段与材料，落库为 pending 待审核，审核通过才计入报名数
     *
     * 这个开关由管理端在活动编辑里按活动单独设置，不影响其他活动。
     */
    @Transactional
    public Map<String, Object> enrollActivity(Long activityId, Long userId, Map<String, Object> body) {
        Map<String, Object> activity = moduleMapper.selectActivity(activityId);
        if (activity == null) throw new IllegalArgumentException("活动不存在或已结束");
        if (userMapper.selectById(userId) == null) throw new IllegalArgumentException("用户不存在");
        if (moduleMapper.countEnrollment(activityId, userId) > 0) throw new IllegalStateException("已经报名过啦");

        String mode = text(activity.get("enrollMode"));
        // 材料报名总开关：关闭时降级为一键报名，避免材料通道出问题导致整场活动无法报名
        if ("material".equals(mode) && featureFlags.isEnabled("enroll.material.enabled")) {
            return submitMaterialEnrollment(activityId, userId, activity, body);
        }
        if ("material".equals(mode)) {
            log.warn("材料报名开关已关闭，活动 {} 降级为一键报名", activityId);
        }

        // 一键报名
        try {
            moduleMapper.insertEnrollment(activityId, userId);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("已经报名过啦");
        }
        if (moduleMapper.incrementEnrollment(activityId) != 1) throw new IllegalArgumentException("活动不存在或已结束");
        return Map.of("enrolled", true, "mode", "simple", "status", "approved");
    }

    /** 材料模式报名：校验表单 + 材料，落 pending，等管理员审核 */
    private Map<String, Object> submitMaterialEnrollment(Long activityId, Long userId, Map<String, Object> activity, Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String realName = text(input.get("realName")).trim();
        String studentNo = text(input.get("studentNo")).trim();
        String phone = text(input.get("phone")).trim();
        String remark = text(input.get("remark")).trim();

        if (realName.isEmpty() || realName.length() > 32) throw new IllegalArgumentException("请填写真实姓名（1-32 字）");
        if (phone.isEmpty() || phone.length() > 20) throw new IllegalArgumentException("请填写联系电话");
        if (studentNo.length() > 32) throw new IllegalArgumentException("学号不能超过 32 字");
        if (remark.length() > 200) throw new IllegalArgumentException("自我介绍不能超过 200 字");

        List<String> materials = new ArrayList<>();
        Object raw = input.get("materials");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String url = text(item).trim();
                if (!url.isEmpty()) materials.add(url);
            }
        }
        int maxFiles = intOr(activity.get("enrollMaxFiles"), 3);
        if (materials.isEmpty()) throw new IllegalArgumentException("请至少上传 1 份材料");
        if (materials.size() > maxFiles) throw new IllegalArgumentException("最多上传 " + maxFiles + " 份材料");
        for (String url : materials) {
            if (url.length() > 255) throw new IllegalArgumentException("材料地址过长");
            if (!url.startsWith("/uploads/") && !url.startsWith("http")) {
                throw new IllegalArgumentException("材料必须是已上传成功的文件");
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("activityId", activityId);
        row.put("userId", userId);
        row.put("status", "pending");
        row.put("realName", realName);
        row.put("studentNo", studentNo);
        row.put("phone", phone);
        row.put("remark", remark);
        moduleMapper.insertEnrollmentWithForm(row);
        Long enrollId = row.get("id") instanceof Number n ? n.longValue() : null;
        if (enrollId == null) throw new IllegalStateException("报名提交失败，请重试");
        for (String url : materials) {
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            moduleMapper.insertEnrollMaterial(enrollId, url, fileName);
        }
        // 注意：这里不累加 enrolled —— 审核通过时才计入报名数
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrolled", true);
        out.put("mode", "material");
        out.put("status", "pending");
        out.put("enrollId", enrollId);
        return out;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value).trim()); } catch (Exception e) { return fallback; }
    }

    // ---- 管理端：报名审核 ----

    public Map<String, Object> enrollments(Long activityId, String status, Long schoolId, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(1, page);
        List<Map<String, Object>> rows = moduleMapper.enrollments(activityId, status, schoolId, safeSize, (safePage - 1) * safeSize);
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            row.put("materials", id instanceof Number n ? moduleMapper.enrollMaterials(n.longValue()) : List.of());
        }
        long total = moduleMapper.countEnrollments(activityId, status, schoolId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows); out.put("total", total); out.put("page", safePage); out.put("size", safeSize);
        return out;
    }

    /** 审核报名：通过时把报名数 +1；驳回要填原因 */
    @Transactional
    public void decideEnrollment(Long enrollId, boolean approve, String reason, Long adminId) {
        Map<String, Object> brief = moduleMapper.enrollBrief(enrollId);
        if (brief == null) throw new IllegalArgumentException("报名记录不存在");
        if (!"pending".equals(text(brief.get("status")))) throw new IllegalStateException("该报名已处理过");
        String why = approve ? "" : text(reason).trim();
        if (!approve && why.isEmpty()) throw new IllegalArgumentException("请填写驳回原因");
        if (why.length() > 200) throw new IllegalArgumentException("驳回原因不能超过 200 字");
        int changed = moduleMapper.decideEnrollment(enrollId, approve ? "approved" : "rejected", why, adminId);
        if (changed != 1) throw new IllegalStateException("该报名已处理过");
        if (approve) {
            Long activityId = brief.get("activityId") instanceof Number n ? n.longValue() : null;
            if (activityId != null) moduleMapper.incrementEnrollment(activityId);
        }
    }

    /** 活动报名设置（管理端按活动开关） */
    public void updateEnrollConfig(Long activityId, String mode, String hint, int maxFiles) {
        Map<String, Object> activity = moduleMapper.selectActivity(activityId);
        if (activity == null) throw new IllegalArgumentException("活动不存在");
        String m = "material".equals(mode) ? "material" : "simple";
        String h = hint == null ? "" : hint.trim();
        if (h.length() > 200) throw new IllegalArgumentException("报名须知不能超过 200 字");
        int max = Math.min(9, Math.max(1, maxFiles));
        moduleMapper.updateActivityEnrollConfig(activityId, m, h, max);
    }

    private Map<String, Object> goodListView(Map<String, Object> row) {
        Map<String, Object> out = goodView(row);
        out.remove("mode");
        out.remove("description");
        return out;
    }

    private Map<String, Object> goodView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("id", "mode", "title", "price", "conditionLevel", "category", "image", "description", "schoolId", "createdAt")) {
            if (row.containsKey(key)) out.put(key, row.get(key));
        }
        List<String> images = new ArrayList<>();
        String imagesText = text(row.get("images"));
        if (!imagesText.isEmpty()) {
            try {
                for (Object entry : json.readValue(imagesText, List.class)) images.add(text(entry));
            } catch (Exception e) {
                // images 存量数据不是合法 JSON 时按单图兜底
                images = new ArrayList<>();
            }
        }
        if (images.isEmpty() && row.get("image") != null && !text(row.get("image")).isEmpty()) images.add(text(row.get("image")));
        out.put("images", images);
        Map<String, Object> seller = new LinkedHashMap<>();
        seller.put("id", row.get("sellerId"));
        seller.put("nickname", row.get("sellerNickname"));
        out.put("seller", seller);
        return out;
    }

    private static String textOr(Object value, String fallback) { String valueText = text(value).trim(); return valueText.isEmpty() ? fallback : valueText; }
    private static Long requiredLong(Object value, String message) { try { long number = Long.parseLong(text(value)); if (number < 1) throw new NumberFormatException(); return number; } catch (NumberFormatException e) { throw new IllegalArgumentException(message); } }
    private static Long longValue(Object value) { if (value instanceof Number number) return number.longValue(); try { return Long.valueOf(String.valueOf(value)); } catch (Exception error) { throw new IllegalStateException("内容编号生成失败", error); } }
    private static int number(Object value) { return value instanceof Number n ? n.intValue() : Integer.parseInt(text(value)); }

    @Transactional
    public Map<String, Object> order(CreateOrderRequest r) {
        Shop shop = requireShop(r.getShopId());
        if (!orderable(shop)) throw new IllegalArgumentException("这家店当前休息或不在营业时间内");
        if (userMapper.selectById(r.getUserId()) == null) throw new IllegalArgumentException("用户不存在");
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (CreateOrderRequest.Item item : r.getItems()) {
            int count = counts.getOrDefault(item.getGoodsId(), 0) + item.getCount();
            if (count > 99) throw new IllegalArgumentException("单件商品最多购买99份");
            counts.put(item.getGoodsId(), count);
        }
        List<ShopGoods> goods = goodsMapper.selectList(new LambdaQueryWrapper<ShopGoods>().eq(ShopGoods::getShopId, shop.getId()).eq(ShopGoods::getStatus, 1).in(ShopGoods::getId, counts.keySet()));
        if (goods.size() != counts.size()) throw new IllegalArgumentException("部分商品已下架或不属于该店铺");
        BigDecimal subtotal = BigDecimal.ZERO;
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (ShopGoods g : goods) {
            int count = counts.get(g.getId());
            subtotal = subtotal.add(g.getPrice().multiply(BigDecimal.valueOf(count)));
            snapshots.add(Map.of("goodsId", g.getId(), "name", g.getName(), "price", g.getPrice(), "count", count));
        }
        if (subtotal.compareTo(shop.getMinPrice()) < 0) throw new IllegalArgumentException("未达到起送金额");
        BigDecimal discount = subtotal.compareTo(shop.getDiscountThreshold()) >= 0 ? shop.getDiscountAmount().min(subtotal) : BigDecimal.ZERO;
        BigDecimal total = subtotal.subtract(discount).add(PACKING_FEE).add(shop.getDeliveryFee()).setScale(2);
        if (total.compareTo(new BigDecimal("99999999.99")) > 0) throw new IllegalArgumentException("订单金额超出限制");
        UserOrder order = new UserOrder();
        // task-14: 收货人姓名/电话快照——优先用请求传入，回退到用户地址簿
        var address = userAddressMapper.selectOne(new LambdaQueryWrapper<com.campuslife.entity.UserAddress>()
            .eq(com.campuslife.entity.UserAddress::getUserId, r.getUserId())
            .eq(com.campuslife.entity.UserAddress::getDetail, r.getAddress().trim())
            .last("LIMIT 1"));
        order.setUserId(r.getUserId()); order.setShopId(shop.getId()); order.setItems(snapshotJson(snapshots)); order.setAddress(r.getAddress().trim());
        order.setContactName(r.getContactName() == null || r.getContactName().isBlank() ? (address == null ? "" : address.getContact()) : r.getContactName().trim());
        order.setContactPhone(r.getContactPhone() == null || r.getContactPhone().isBlank() ? (address == null ? "" : address.getPhone()) : r.getContactPhone().trim());
        order.setRemark(r.getRemark() == null ? "" : r.getRemark().trim()); order.setSubtotal(subtotal); order.setPackingFee(PACKING_FEE); order.setDeliveryFee(shop.getDeliveryFee()); order.setDiscountAmount(discount); order.setTotal(total);
        // task-12: 支付字段在下单时一并初始化，支付回调只更新这几列
        order.setStatus(OrderStatus.CREATED.value());
        order.setPayAmountCents(total.movePointRight(2).setScale(0).intValueExact());
        order.setPayStatus("unpaid");
        order.setOutTradeNo("CL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        order.setCreatedAt(LocalDateTime.now());
        orderMapper.insert(order);
        // 真实热度：下单成功才累加店铺订单数（与订单写入同一事务）
        recommendMapper.increaseShopOrder(shop.getId());
        return Map.of("orderId", order.getId(), "id", order.getId(), "total", total, "payAmountCents", order.getPayAmountCents(), "outTradeNo", order.getOutTradeNo(), "payStatus", order.getPayStatus());
    }

    private String snapshotJson(List<Map<String, Object>> items) { try { return json.writeValueAsString(items); } catch (JsonProcessingException e) { throw new IllegalStateException("订单商品保存失败", e); } }
}



