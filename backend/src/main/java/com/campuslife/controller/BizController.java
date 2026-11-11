package com.campuslife.controller;

import com.campuslife.config.CurrentUser;
import com.campuslife.dto.ApiResponse;
import com.campuslife.dto.CreateOrderRequest;
import com.campuslife.config.UnauthorizedException;
import com.campuslife.service.BizService;
import com.campuslife.service.MerchantService;
import com.campuslife.service.PayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BizController {
    private final BizService service;
    private final MerchantService merchantService;
    private final PayService payService;

    @GetMapping({"/shops", "/food/shops"})
    public ApiResponse<List<Map<String,Object>>> shops(@RequestParam(defaultValue = "") String category,
                                         @RequestParam(defaultValue = "local") String schoolScope,
                                         @RequestParam(required = false) Long schoolId,
                                         @RequestParam(defaultValue = "1") Long userId,
                                         HttpServletRequest request) {
        return ApiResponse.ok(service.shops(category, schoolScope, schoolId, CurrentUser.resolve(request, userId)));
    }

    @GetMapping({"/shops/{id}/menu", "/food/shops/{id}/menu"})
    public ApiResponse<List<Map<String, Object>>> menu(@PathVariable Long id) { return ApiResponse.ok(service.menu(id)); }

    @PostMapping("/orders")
    public ApiResponse<Map<String, Object>> order(@Valid @RequestBody CreateOrderRequest request, HttpServletRequest servletRequest) {
        request.setUserId(requireUser(servletRequest));
        return ApiResponse.ok(service.order(request));
    }

    @GetMapping("/orders")
    public ApiResponse<Map<String,Object>> myOrders(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(merchantService.userOrders(requireUser(request), page, size));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<Map<String,Object>> myOrder(@PathVariable Long id,
                                                   @RequestParam(required = false) Long userId,
                                                   HttpServletRequest request) {
        Long authedId = requireUser(request);
        // 小程序两种调用方式都支持：token 鉴权；若显式传了 userId 则要求与 token 一致（防越权）
        if (userId != null && !userId.equals(authedId)) throw new UnauthorizedException("不能查看其他用户的订单");
        // 统一走 PayService.detail：字段最全（支付/退款记录 + 骑手配送信息），小程序订单详情页依赖它
        Map<String, Object> detail = payService.detail(id);
        if (!authedId.equals(detail.get("userId"))) throw new UnauthorizedException("无权查看该订单");
        // F10 交易埋点：这一单实际到账的积分（0 = 没发）。由 MerchantService 读 point_log 判定，
        // 不信任订单状态自行推断 —— 模拟支付/退款/日上限命中都没有流水。
        detail.put("pointsAwarded", merchantService.pointsAwardedForOrder(id, authedId));
        return ApiResponse.ok(detail);
    }

    /** 「我的数据」接口专用：必须是登录用户。 */
    private static Long requireUser(HttpServletRequest request) {
        Long id = CurrentUser.optional(request);
        if (id == null || id < 1) throw new UnauthorizedException("请先登录");
        return id;
    }

    /** 写接口用：token 优先，匿名时回落调用方上报值（仅 campus.auth.enforce=false 时可能发生）。 */
    private static Long requireUser(HttpServletRequest request, Long claimed) {
        Long id = CurrentUser.resolve(request, claimed, null);
        if (id == null || id < 1) throw new UnauthorizedException("请先登录");
        return id;
    }

    /**
     * 二手列表。
     *
     * 兼容两种调用：
     *   - 不传 page：返回原来的扁平数组（老调用方不受影响）；
     *   - 传 page（小程序触底加载用）：返回 {list,total,page,size}。
     * 这样分页能力是"加法"，不会因为改分页把现有页面弄坏。
     */
    @GetMapping("/goods")
    public ApiResponse<Object> goods(@RequestParam(defaultValue = "1") Integer mode,
                                     @RequestParam(defaultValue = "") String category,
                                     @RequestParam(defaultValue = "local") String schoolScope,
                                     @RequestParam(required = false) Long schoolId,
                                     @RequestParam(defaultValue = "1") Long userId,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(defaultValue = "20") int size,
                                     HttpServletRequest request) {
        Long self = CurrentUser.resolve(request, userId);
        if (page != null) {
            return ApiResponse.ok(service.goodsPaged(mode, category, schoolScope, schoolId, self, page, size));
        }
        return ApiResponse.ok(service.goods(mode, category, schoolScope, schoolId, self));
    }

    @GetMapping("/goods/{id}")
    public ApiResponse<Map<String, Object>> good(@PathVariable Long id) { return ApiResponse.ok(service.good(id)); }

    @PostMapping("/goods")
    public ApiResponse<Map<String, Object>> publishGood(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        body.put("userId", requireUser(request));
        return ApiResponse.ok(service.publishGood(body));
    }

    // 下架/删除自己发布的商品
    @DeleteMapping("/goods/{id}")
    public ApiResponse<Object> deleteGood(@PathVariable Long id,
                                          @RequestParam(required = false) Long userId,
                                          HttpServletRequest request) {
        // 删自己的商品：身份以 token 为准，传了别人的 userId 一律按 token 的用户处理
        service.deleteGood(id, CurrentUser.resolve(request, userId));
        return ApiResponse.ok(null);
    }

    // 兼职列表/发布已迁到 JobController（/api/jobs）：岗位域需要状态机、闸门与履约，
    // 放在这里会和 JobController 的路径映射冲突。旧调用方（settle/keyword/page/size）保持不变。

    // 我发布的内容计数（帖子/闲置/兼职/活动）
    @GetMapping("/users/{id}/publish-count")
    public ApiResponse<Map<String, Object>> publishCount(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.myPublishCount(CurrentUser.resolve(request, id)));
    }

    /** 活动列表：传 page 返回分页对象，不传返回原扁平数组（兼容老调用）。 */
    @GetMapping("/activities")
    public ApiResponse<Object> activities(@RequestParam(defaultValue = "1") Long userId,
                                          @RequestParam(defaultValue = "local") String schoolScope,
                                          @RequestParam(required = false) Long schoolId,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(defaultValue = "20") int size,
                                          HttpServletRequest request) {
        Long self = CurrentUser.resolve(request, userId);
        if (page != null) {
            return ApiResponse.ok(service.activitiesPaged(self, schoolScope, schoolId, page, size));
        }
        return ApiResponse.ok(service.activities(self, schoolScope, schoolId));
    }

    // 发布活动：对应「发布」页的「活动组队」选项
    @PostMapping("/activities")
    public ApiResponse<Map<String, Object>> publishActivity(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        body.put("userId", requireUser(request));
        return ApiResponse.ok(service.publishActivity(body));
    }

    @PostMapping("/activities/{id}/enroll")
    public ApiResponse<Map<String, Object>> enrollActivity(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request) {
        Long claimed = null;
        if (body != null && body.get("userId") != null) {
            try { claimed = Long.parseLong(String.valueOf(body.get("userId"))); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("用户不存在"); }
        }
        // 报名模式由活动自己决定：simple 一键报名；material 需要表单 + 材料
        return ApiResponse.ok(service.enrollActivity(id, requireUser(request, claimed), body));
    }
}
