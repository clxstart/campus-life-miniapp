package com.campuslife.service;

import com.campuslife.config.ForbiddenException;
import com.campuslife.mapper.AdminModuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

// 管理端各模块业务。
// 数据隔离：超管传 schoolId=null（看全部），校管强制用自己的 schoolId。
@Service @RequiredArgsConstructor
public class AdminModuleService {
    private final AuditService auditService;
    private final AdminModuleMapper mapper;

    // ---- 通用工具 ----
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String trim(Object value) { return text(value).trim(); }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(trim(value)); } catch (Exception e) { return fallback; }
    }

    private static long longOr(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(trim(value)); } catch (Exception e) { return fallback; }
    }

    private static String like(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return "";
        if (kw.length() > 64) throw new IllegalArgumentException("搜索词不能超过64字");
        return "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static Map<String, Object> page(List<Map<String, Object>> list, long total, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    // 分页参数规范化
    private static int[] paging(int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return new int[]{p, s, (p - 1) * s};
    }

    // ---- 看板 ----
    public Map<String, Object> dashboard(Long schoolId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Object> raw = mapper.dashboard(schoolId);
        if (raw != null) for (Map.Entry<String, Object> e : raw.entrySet()) stats.put(e.getKey(), longOr(e.getValue(), 0));
        List<Map<String, Object>> trend = mapper.postTrend(schoolId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", stats);
        out.put("postTrend", trend);
        return out;
    }

    // ---- 学校 ----
    public List<Map<String, Object>> schools() { return mapper.schools(); }

    public Map<String, Object> createSchool(Map<String, Object> body) {
        String name = trim(body.get("name"));
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("学校名称须为1至64字");
        String city = trim(body.get("city"));
        if (city.length() > 32) throw new IllegalArgumentException("城市不能超过32字");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("city", city);
        row.put("logo", trim(body.get("logo")));
        row.put("periodConfig", trim(body.get("periodConfig")));
        mapper.insertSchool(row);
        return Map.of("id", row.get("id"));
    }

    public void updateSchool(Long id, Map<String, Object> body) {
        Map<String, Object> row = new LinkedHashMap<>();
        String name = trim(body.get("name"));
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("学校名称须为1至64字");
        row.put("id", id);
        row.put("name", name);
        row.put("city", trim(body.get("city")));
        row.put("logo", trim(body.get("logo")));
        row.put("periodConfig", trim(body.get("periodConfig")));
        row.put("status", intOr(body.get("status"), 1) == 0 ? 0 : 1);
        // 影响行数为 0 说明这个 id 不存在（可能刚被别的管理员删掉），不能静默返回成功
        if (mapper.updateSchool(row) != 1) throw new IllegalArgumentException("学校不存在");
    }

    // ---- 用户 ----
    public Map<String, Object> users(Long schoolId, String keyword, int page, int size) {
        int[] p = paging(page, size);
        String kw = like(keyword);
        return page(mapper.users(schoolId, kw, p[1], p[2]), mapper.countUsers(schoolId, kw), p[0], p[1]);
    }

    // ---- 内容 ----
    public Map<String, Object> posts(Long schoolId, String keyword, boolean onlyOffline, int page, int size) {
        int[] p = paging(page, size);
        String kw = like(keyword);
        return page(mapper.posts(schoolId, kw, onlyOffline, p[1], p[2]), mapper.countPosts(schoolId, kw, onlyOffline), p[0], p[1]);
    }

    // 下架/恢复：校管只能动本校内容
    public void setPostStatus(Long schoolId, Long id, int status) {
        Map<String, Object> brief = mapper.postBrief(id);
        if (brief == null) throw new IllegalArgumentException("帖子不存在");
        assertSameSchool(schoolId, brief.get("schoolId"));
        mapper.setPostStatus(id, status == 1 ? 1 : 0);
    }

    public void deletePost(Long schoolId, Long id) {
        Map<String, Object> brief = mapper.postBrief(id);
        if (brief == null) throw new IllegalArgumentException("帖子不存在");
        assertSameSchool(schoolId, brief.get("schoolId"));
        mapper.deletePost(id);
    }

    // ---- 内容编辑（帖子 / 二手 / 活动）----
    // 运营修正写错的标题、换张配图，不必让用户删了重发。

    public void updatePostContent(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> brief = mapper.postBrief(id);
        if (brief == null) throw new IllegalArgumentException("帖子不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", requireText(body.get("title"), "标题", 64));
        row.put("content", requireText(body.get("content"), "正文", 5000));
        row.put("image", imageOrEmpty(body.get("image"), "配图"));
        row.put("tag", trim(body.get("tag")));
        mapper.updatePostContent(row);
    }

    public void updateGoodsContent(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> brief = mapper.goodsBrief(id);
        if (brief == null) throw new IllegalArgumentException("商品不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));

        String priceText = trim(body.get("price"));
        BigDecimal price;
        try {
            price = new BigDecimal(priceText.isEmpty() ? "0" : priceText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("价格格式不正确");
        }
        if (price.signum() < 0 || price.compareTo(new BigDecimal("99999")) > 0) {
            throw new IllegalArgumentException("价格须为0至99999");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", requireText(body.get("title"), "标题", 64));
        row.put("price", price);
        row.put("conditionLevel", requireText(body.get("conditionLevel"), "成色", 8));
        row.put("category", requireText(body.get("category"), "分类", 16));
        row.put("image", imageOrEmpty(body.get("image"), "图片"));
        row.put("description", trim(body.get("description")));
        mapper.updateGoodsContent(row);
    }

    public void updateActivityContent(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> brief = mapper.activityBrief(id);
        if (brief == null) throw new IllegalArgumentException("活动不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", requireText(body.get("title"), "标题", 64));
        row.put("poster", imageOrEmpty(body.get("poster"), "海报"));
        row.put("timeDesc", trim(body.get("timeDesc")));
        row.put("location", trim(body.get("location")));
        row.put("description", trim(body.get("description")));
        mapper.updateActivityContent(row);
    }

    // ---- 用户头像 ----
    public void setUserAvatar(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> brief = mapper.userBrief(id);
        if (brief == null) throw new IllegalArgumentException("用户不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        mapper.setUserAvatar(id, imageOrEmpty(body.get("avatar"), "头像"));
    }

    /**
     * 图片字段统一校验：空串允许（表示清空），非空必须是服务端可访问地址。
     * 与发布接口用同一套规则，避免管理端成为脏数据的后门。
     */
    private static String imageOrEmpty(Object value, String label) {
        String url = trim(value);
        if (url.isEmpty()) return "";
        if (url.length() > 255) throw new IllegalArgumentException(label + "地址过长");
        if (!isServableImage(url)) throw new IllegalArgumentException(label + "未上传成功，请重新选择图片");
        return url;
    }

    /** 与 BizService.isServableImage 同一套规则：拒绝微信本地临时路径 */
    private static boolean isServableImage(String url) {
        if (url == null) return false;
        String value = url.trim();
        if (value.isEmpty()) return false;
        if (value.startsWith("wxfile://")) return false;
        if (value.startsWith("http://tmp/") || value.startsWith("https://tmp/")) return false;
        if (value.startsWith("http://") || value.startsWith("https://")) return true;
        return value.startsWith("/uploads/") || value.startsWith("/assets/");
    }

    // 校管不能操作别校数据；跨校内容（schoolId=null）只有超管能动
    private static void assertSameSchool(Long scopeSchoolId, Object rowSchoolId) {
        if (scopeSchoolId == null) return;
        long row = longOr(rowSchoolId, -1);
        if (row != scopeSchoolId.longValue()) throw new ForbiddenException("无权操作其他学校的数据");
    }


    // ---- 外卖：店铺 ----
    // 小程序有完整外卖功能，但管理端原来没有页面，店铺只能靠 SQL 维护。

    /**
     * 外卖店铺列表
     *
     * 除了上架状态 status，还额外计算「当前是否可接单」（orderable），
     * 让商家/管理员能看出「已上架但现在不能下单」的情况：
     *   - status=0            → 已下架
     *   - is_open=0           → 手动打烊
     *   - 不在营业时间段内     → 已过营业时间
     * 之前管理端只看 status，出现「后台显示营业中、小程序显示休息中」的困惑。
     */
    public Map<String, Object> shops(Long schoolId, String keyword, int page, int size) {
        int[] p = paging(page, size);
        String kw = like(keyword);
        List<Map<String, Object>> list = mapper.shops(schoolId, kw, p[1], p[2]);
        list.forEach(this::decorateShopOpenState);
        return page(list, mapper.countShops(schoolId, kw), p[0], p[1]);
    }

    /** 为店铺行补充 orderable / openStateText，与小程序端 orderable() 判定保持一致 */
    private void decorateShopOpenState(Map<String, Object> row) {
        boolean listed = Integer.valueOf(1).equals(numberOrNull(row.get("status")));
        boolean manualOpen = Integer.valueOf(1).equals(numberOrNull(row.get("isOpen")));

        boolean inBusinessHours = true;
        String range = "";
        Object openRaw = row.get("openTime");
        Object closeRaw = row.get("closeTime");
        if (openRaw != null && closeRaw != null) {
            String open = String.valueOf(openRaw);
            String close = String.valueOf(closeRaw);
            range = open + "-" + close;
            try {
                LocalTime openT = LocalTime.parse(open);
                LocalTime closeT = LocalTime.parse(close);
                if (openT.equals(closeT)) {
                    inBusinessHours = true;                 // 24 小时营业
                } else {
                    LocalTime now = LocalTime.now();
                    inBusinessHours = openT.isBefore(closeT)
                        ? !now.isBefore(openT) && now.isBefore(closeT)
                        : !now.isBefore(openT) || now.isBefore(closeT);   // 跨天
                }
            } catch (Exception ignored) {
                inBusinessHours = true;                     // 时间格式异常时不阻塞
            }
        }

        boolean orderable = listed && manualOpen && inBusinessHours;
        row.put("orderable", orderable);

        String stateText;
        if (!listed) {
            stateText = "已下架";
        } else if (!manualOpen) {
            stateText = "已打烊";
        } else if (!inBusinessHours) {
            stateText = "已过营业时间" + (range.isEmpty() ? "" : "（" + range + "）");
        } else {
            stateText = "可接单";
        }
        row.put("openStateText", stateText);
    }

    private static Integer numberOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }

    public Map<String, Object> createShop(Long scopeSchoolId, Map<String, Object> body) {
        Long schoolId = resolveTargetSchool(scopeSchoolId, longOr(body.get("schoolId"), 0));
        Map<String, Object> row = shopRow(body);
        row.put("schoolId", schoolId);
        row.put("rating", new BigDecimal("5.0"));
        mapper.insertShop(row);
        return Map.of("id", row.get("id"));
    }

    public void updateShop(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> brief = mapper.shopBrief(id);
        if (brief == null) throw new IllegalArgumentException("店铺不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        Map<String, Object> row = shopRow(body);
        row.put("id", id);
        mapper.updateShop(row);
    }

    public void setShopStatus(Long scopeSchoolId, Long id, int status) {
        Map<String, Object> brief = mapper.shopBrief(id);
        if (brief == null) throw new IllegalArgumentException("店铺不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        mapper.setShopStatus(id, status == 0 ? 0 : 1);
    }

    public void deleteShop(Long scopeSchoolId, Long id) {
        Map<String, Object> brief = mapper.shopBrief(id);
        if (brief == null) throw new IllegalArgumentException("店铺不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        mapper.deleteShop(id);
    }

    private static Map<String, Object> shopRow(Map<String, Object> body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", requireText(body.get("name"), "店铺名称", 64));
        row.put("cover", imageOrEmpty(body.get("cover"), "店铺封面"));
        row.put("category", requireText(body.get("category"), "分类", 32));
        row.put("minPrice", moneyOr(body.get("minPrice"), "起送价"));
        row.put("deliveryFee", moneyOr(body.get("deliveryFee"), "配送费"));
        row.put("deliveryTime", Math.max(1, intOr(body.get("deliveryTime"), 30)));
        row.put("discount", trim(body.get("discount")));
        row.put("notice", trim(body.get("notice")));
        row.put("status", intOr(body.get("status"), 1) == 0 ? 0 : 1);
        return row;
    }

    private static BigDecimal moneyOr(Object value, String label) {
        String text = trim(value);
        BigDecimal amount;
        try {
            amount = new BigDecimal(text.isEmpty() ? "0" : text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "格式不正确");
        }
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("99999")) > 0) {
            throw new IllegalArgumentException(label + "须为0至99999");
        }
        return amount;
    }

    // ---- 外卖：菜品 ----

    public List<Map<String, Object>> shopGoods(Long scopeSchoolId, Long shopId) {
        Map<String, Object> brief = mapper.shopBrief(shopId);
        if (brief == null) throw new IllegalArgumentException("店铺不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        return mapper.shopGoods(shopId);
    }

    public Map<String, Object> createShopGoods(Long scopeSchoolId, Map<String, Object> body) {
        Long shopId = longOr(body.get("shopId"), 0);
        Map<String, Object> brief = mapper.shopBrief(shopId);
        if (brief == null) throw new IllegalArgumentException("店铺不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        Map<String, Object> row = shopGoodsRow(body);
        row.put("shopId", shopId);
        mapper.insertShopGoods(row);
        return Map.of("id", row.get("id"));
    }

    public void updateShopGoods(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> brief = mapper.shopGoodsBrief(id);
        if (brief == null) throw new IllegalArgumentException("菜品不存在");
        Map<String, Object> shop = mapper.shopBrief(longOr(brief.get("shopId"), 0));
        assertSameSchool(scopeSchoolId, shop == null ? null : shop.get("schoolId"));
        Map<String, Object> row = shopGoodsRow(body);
        row.put("id", id);
        mapper.updateShopGoods(row);
    }

    public void deleteShopGoods(Long scopeSchoolId, Long id) {
        Map<String, Object> brief = mapper.shopGoodsBrief(id);
        if (brief == null) throw new IllegalArgumentException("菜品不存在");
        Map<String, Object> shop = mapper.shopBrief(longOr(brief.get("shopId"), 0));
        assertSameSchool(scopeSchoolId, shop == null ? null : shop.get("schoolId"));
        mapper.deleteShopGoods(id);
    }

    private static Map<String, Object> shopGoodsRow(Map<String, Object> body) {
        BigDecimal price = moneyOr(body.get("price"), "价格");
        if (price.signum() <= 0) throw new IllegalArgumentException("价格须大于0");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("category", requireText(body.get("category"), "菜品分类", 32));
        row.put("name", requireText(body.get("name"), "菜品名称", 64));
        row.put("price", price);
        row.put("image", imageOrEmpty(body.get("image"), "菜品图片"));
        row.put("sortNum", intOr(body.get("sortNum"), 0));
        row.put("status", intOr(body.get("status"), 1) == 0 ? 0 : 1);
        return row;
    }

    // ---- 校园评选：选手 ----
    // 全平台活动，不按校隔离（idol_contestant 没有 school_id）。

    public List<Map<String, Object>> idolContestants(Integer gender) {
        Integer g = (gender != null && (gender == 1 || gender == 2)) ? gender : null;
        return mapper.idolContestants(g);
    }

    public Map<String, Object> createIdolContestant(Map<String, Object> body) {
        Map<String, Object> row = idolRow(body);
        row.put("userId", longOr(body.get("userId"), 1));
        mapper.insertIdolContestant(row);
        return Map.of("id", row.get("id"));
    }

    public void updateIdolContestant(Long id, Map<String, Object> body) {
        if (mapper.idolContestantBrief(id) == null) throw new IllegalArgumentException("选手不存在");
        Map<String, Object> row = idolRow(body);
        row.put("id", id);
        mapper.updateIdolContestant(row);
    }

    public void setIdolContestantStatus(Long id, int status) {
        if (mapper.idolContestantBrief(id) == null) throw new IllegalArgumentException("选手不存在");
        mapper.setIdolContestantStatus(id, status == 0 ? 0 : 1);
    }

    public void deleteIdolContestant(Long id) {
        if (mapper.idolContestantBrief(id) == null) throw new IllegalArgumentException("选手不存在");
        mapper.deleteIdolContestant(id);
    }

    private static Map<String, Object> idolRow(Map<String, Object> body) {
        int gender = intOr(body.get("gender"), 0);
        if (gender != 1 && gender != 2) throw new IllegalArgumentException("性别只能是 1（男）或 2（女）");
        String photo = imageOrEmpty(body.get("photo"), "照片");
        if (photo.isEmpty()) throw new IllegalArgumentException("请上传选手照片");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", requireText(body.get("name"), "选手姓名", 32));
        row.put("gender", gender);
        row.put("college", trim(body.get("college")));
        row.put("photo", photo);
        row.put("slogan", trim(body.get("slogan")));
        row.put("status", intOr(body.get("status"), 1) == 0 ? 0 : 1);
        return row;
    }
    // ---- 每校自定义地点 ----
    // 发帖/发兼职选的地点。每校维护自己的一套，管理端配置、小程序按选中学校拉。

    public List<Map<String, Object>> schoolLocations(Long scopeSchoolId, Long requestedSchoolId) {
        Long schoolId = resolveTargetSchool(scopeSchoolId, requestedSchoolId);
        return mapper.schoolLocations(schoolId);
    }

    public Map<String, Object> createSchoolLocation(Long scopeSchoolId, Map<String, Object> body) {
        Long schoolId = resolveTargetSchool(scopeSchoolId, longOr(body.get("schoolId"), 0));
        String name = requireText(body.get("name"), "地点名称", 32);
        assertLocationNameFree(schoolId, name, 0L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schoolId", schoolId);
        row.put("name", name);
        row.put("sort", intOr(body.get("sort"), 0));
        row.put("status", intOr(body.get("status"), 1) == 0 ? 0 : 1);
        mapper.insertSchoolLocation(row);
        return Map.of("id", row.get("id"));
    }

    public void updateSchoolLocation(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> brief = mapper.schoolLocationBrief(id);
        if (brief == null) throw new IllegalArgumentException("地点不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        Long schoolId = longOr(brief.get("schoolId"), 0);
        String name = requireText(body.get("name"), "地点名称", 32);
        assertLocationNameFree(schoolId, name, id);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("schoolId", schoolId);
        row.put("name", name);
        row.put("sort", intOr(body.get("sort"), 0));
        row.put("status", intOr(body.get("status"), 1) == 0 ? 0 : 1);
        mapper.updateSchoolLocation(row);
    }

    public void deleteSchoolLocation(Long scopeSchoolId, Long id) {
        Map<String, Object> brief = mapper.schoolLocationBrief(id);
        if (brief == null) throw new IllegalArgumentException("地点不存在");
        assertSameSchool(scopeSchoolId, brief.get("schoolId"));
        mapper.deleteSchoolLocation(id, longOr(brief.get("schoolId"), 0));
    }

    // 目标学校：校管只能操作本校；超管必须显式指定
    private static Long resolveTargetSchool(Long scopeSchoolId, Long requested) {
        if (scopeSchoolId != null) {
            if (requested != null && requested > 0 && !requested.equals(scopeSchoolId)) {
                throw new ForbiddenException("无权操作其他学校的数据");
            }
            return scopeSchoolId;
        }
        if (requested == null || requested < 1) throw new IllegalArgumentException("请指定所属学校");
        return requested;
    }

    // 同校地点不能重名（数据库有唯一键，这里先给出可读的中文提示）
    private void assertLocationNameFree(Long schoolId, String name, Long excludeId) {
        if (mapper.locationNameTaken(schoolId, name, excludeId == null ? 0L : excludeId) > 0) {
            throw new IllegalArgumentException("该校已有同名地点，请换一个名称");
        }
    }

    // ---- 圈子 ----
    // 圈子列表：分页返回，避免多校铺开后一次拉全库
    public Map<String, Object> circles(Long schoolId, String keyword, int page, int size) {
        int[] p = paging(page, size);
        String kw = like(keyword);
        return page(mapper.circles(schoolId, kw, p[1], p[2]),
                    mapper.countCircles(schoolId, kw), p[0], p[1]);
    }

    @Transactional
    public Map<String, Object> createCircle(Long schoolId, Map<String, Object> body) {
        String name = trim(body.get("name"));
        if (name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("圈子名称须为1至32字");
        String description = trim(body.get("description"));
        var textAudit = auditService.checkText(name + "\n" + description, "circle");
        auditService.rejectWithLog(textAudit, "circle", null, name + "\n" + description);
        String slug = trim(body.get("slug"));
        if (slug.isEmpty()) slug = "c" + System.currentTimeMillis();
        if (!slug.matches("^[a-zA-Z0-9_-]{1,32}$")) throw new IllegalArgumentException("标识只能是字母数字下划线短横线");
        if (mapper.slugTaken(slug, 0L) > 0) throw new IllegalArgumentException("该标识已被占用");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("slug", slug);
        row.put("category", trim(body.get("category")).isEmpty() ? "兴趣" : trim(body.get("category")));
        row.put("description", description);
        row.put("cover", trim(body.get("cover")));
        row.put("theme", trim(body.get("theme")).isEmpty() ? "blue" : trim(body.get("theme")));
        row.put("hot", intOr(body.get("hot"), 0) == 1 ? 1 : 0);
        row.put("sort", intOr(body.get("sort"), 0));
        row.put("schoolId", schoolId);
        String auditStatus = auditService.initialStatus(textAudit, false);
        row.put("auditStatus", auditStatus);
        row.put("auditReason", textAudit.reason());
        row.put("auditAt", "passed".equals(auditStatus) ? java.time.LocalDateTime.now() : null);
        row.put("status", "passed".equals(auditStatus) ? (intOr(body.get("status"), 1) == 0 ? 0 : 1) : 0);
        mapper.insertCircle(row);
        auditService.logTextResult("circle", longOr(row.get("id"), 0), null, name + "\n" + description, textAudit);
        return Map.of("id", row.get("id"));
    }

    @Transactional
    public void updateCircle(Long schoolId, Long id, Map<String, Object> body) {
        Map<String, Object> row = new LinkedHashMap<>();
        String name = trim(body.get("name"));
        if (name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("圈子名称须为1至32字");
        String description = trim(body.get("description"));
        var textAudit = auditService.checkText(name + "\n" + description, "circle");
        auditService.rejectWithLog(textAudit, "circle", null, name + "\n" + description);
        row.put("id", id);
        row.put("name", name);
        row.put("category", trim(body.get("category")).isEmpty() ? "兴趣" : trim(body.get("category")));
        row.put("description", description);
        row.put("cover", trim(body.get("cover")));
        row.put("theme", trim(body.get("theme")).isEmpty() ? "blue" : trim(body.get("theme")));
        row.put("hot", intOr(body.get("hot"), 0) == 1 ? 1 : 0);
        row.put("sort", intOr(body.get("sort"), 0));
        String auditStatus = auditService.initialStatus(textAudit, false);
        row.put("auditStatus", auditStatus);
        row.put("auditReason", textAudit.reason());
        row.put("auditAt", "passed".equals(auditStatus) ? java.time.LocalDateTime.now() : null);
        row.put("status", "passed".equals(auditStatus) && intOr(body.get("status"), 1) == 1 ? 1 : 0);
        // 影响行数为 0 说明圈子不存在；审核日志也只在该圈子真的被改动时才写
        if (mapper.updateCircle(row) != 1) throw new IllegalArgumentException("圈子不存在");
        auditService.logTextResult("circle", id, null, name + "\n" + description, textAudit);
    }

    // ---- 学期 ----
    public List<Map<String, Object>> terms(Long schoolId) { return mapper.terms(schoolId); }

    public Map<String, Object> createTerm(Long scopeSchoolId, Map<String, Object> body) {
        Long schoolId = scopeSchoolId != null ? scopeSchoolId : longOr(body.get("schoolId"), 0);
        if (schoolId < 1) throw new IllegalArgumentException("请指定学校");
        Map<String, Object> school = mapper.schoolBrief(schoolId);
        if (school == null) throw new IllegalArgumentException("学校不存在");
        String label = trim(body.get("termLabel"));
        if (label.isEmpty() || label.length() > 64) throw new IllegalArgumentException("学期名称须为1至64字");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("school", school.get("name"));
        row.put("schoolId", schoolId);
        row.put("termLabel", label);
        row.put("startDate", requireDate(body.get("startDate"), "开学日期"));
        row.put("endDate", requireDate(body.get("endDate"), "结束日期"));
        row.put("midtermStart", requireDate(body.get("midtermStart"), "期中开始"));
        row.put("midtermEnd", requireDate(body.get("midtermEnd"), "期中结束"));
        row.put("finalStart", requireDate(body.get("finalStart"), "期末开始"));
        row.put("active", intOr(body.get("active"), 1) == 0 ? 0 : 1);
        mapper.insertTerm(row);
        return Map.of("id", row.get("id"));
    }

    public void updateTerm(Long scopeSchoolId, Long id, Map<String, Object> body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("termLabel", requireText(body.get("termLabel"), "学期名称", 64));
        row.put("startDate", requireDate(body.get("startDate"), "开学日期"));
        row.put("endDate", requireDate(body.get("endDate"), "结束日期"));
        row.put("midtermStart", requireDate(body.get("midtermStart"), "期中开始"));
        row.put("midtermEnd", requireDate(body.get("midtermEnd"), "期中结束"));
        row.put("finalStart", requireDate(body.get("finalStart"), "期末开始"));
        row.put("active", intOr(body.get("active"), 1) == 0 ? 0 : 1);
        mapper.updateTerm(row);
    }

    private static String requireDate(Object value, String label) {
        String text = trim(value);
        if (text.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        try { LocalDate.parse(text); } catch (Exception e) { throw new IllegalArgumentException(label + "格式应为 yyyy-MM-dd"); }
        return text;
    }

    private static String requireText(Object value, String label, int max) {
        String text = trim(value);
        if (text.isEmpty() || text.length() > max) throw new IllegalArgumentException(label + "须为1至" + max + "字");
        return text;
    }

    /**
     * 删除学期。
     *
     * 学期原来只能新增和编辑，建错了就永远留在列表里（多校配置时尤其容易建错）。
     * 校管只能删自己学校的学期，超管不限。
     */
    public void deleteTerm(Long scopeSchoolId, Long id) {
        Map<String, Object> brief = mapper.termBrief(id);
        if (brief == null) throw new IllegalArgumentException("学期不存在");
        if (scopeSchoolId != null) {
            Object own = brief.get("schoolId");
            long termSchool = own instanceof Number n ? n.longValue() : 0L;
            if (termSchool != scopeSchoolId) throw new ForbiddenException("只能删除本校的学期");
        }
        if (mapper.deleteTerm(id) != 1) throw new IllegalArgumentException("学期不存在");
    }

    // ---- 业务 ----
    public Map<String, Object> jobs(Long schoolId, String keyword, int page, int size) {
        int[] p = paging(page, size);
        String kw = like(keyword);
        return page(mapper.jobs(schoolId, kw, p[1], p[2]), mapper.countJobs(schoolId, kw), p[0], p[1]);
    }

    public void deleteJob(Long id) { mapper.deleteJob(id); }

    public Map<String, Object> activities(Long schoolId, String keyword, int page, int size) {
        int[] p = paging(page, size);
        String kw = like(keyword);
        return page(mapper.activities(schoolId, kw, p[1], p[2]), mapper.countActivities(schoolId, kw), p[0], p[1]);
    }

    public void setActivityStatus(Long id, int status) { mapper.setActivityStatus(id, status == 1 ? 1 : 0); }

    public Map<String, Object> goods(Long schoolId, String keyword, int page, int size) {
        int[] p = paging(page, size);
        String kw = like(keyword);
        return page(mapper.goods(schoolId, kw, p[1], p[2]), mapper.countGoods(schoolId, kw), p[0], p[1]);
    }

    public void setGoodsStatus(Long id, int status) { mapper.setGoodsStatus(id, status == 1 ? 1 : 0); }

    // ---- 运营 ----
    // Banner 列表：分页返回
    public Map<String, Object> banners(Long schoolId, int page, int size) {
        int[] p = paging(page, size);
        return page(mapper.banners(schoolId, p[1], p[2]),
                    mapper.countBanners(schoolId), p[0], p[1]);
    }

    public Map<String, Object> createBanner(Long schoolId, Map<String, Object> body) {
        Map<String, Object> row = bannerRow(body, schoolId);
        mapper.insertBanner(row);
        return Map.of("id", row.get("id"));
    }

    public void updateBanner(Long id, Map<String, Object> body) {
        Map<String, Object> row = bannerRow(body, null);
        row.put("id", id);
        if (mapper.updateBanner(row) != 1) throw new IllegalArgumentException("轮播位不存在");
    }

    public void deleteBanner(Long id) { mapper.deleteBanner(id); }

    private static Map<String, Object> bannerRow(Map<String, Object> body, Long schoolId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slot", trim(body.get("slot")).isEmpty() ? "discover" : trim(body.get("slot")));
        row.put("eyebrow", trim(body.get("eyebrow")));
        row.put("title", requireText(body.get("title"), "标题", 64));
        row.put("subtitle", trim(body.get("subtitle")));
        row.put("badge", trim(body.get("badge")));
        row.put("theme", trim(body.get("theme")).isEmpty() ? "blue" : trim(body.get("theme")));
        String linkType = trim(body.get("linkType"));
        row.put("linkType", "page".equals(linkType) ? "page" : "none");
        row.put("linkValue", trim(body.get("linkValue")));
        row.put("sort", intOr(body.get("sort"), 0));
        row.put("status", intOr(body.get("status"), 1) == 0 ? 0 : 1);
        if (schoolId != null) row.put("schoolId", schoolId);
        else row.put("schoolId", body.get("schoolId") == null ? null : longOr(body.get("schoolId"), 0));
        return row;
    }
}
