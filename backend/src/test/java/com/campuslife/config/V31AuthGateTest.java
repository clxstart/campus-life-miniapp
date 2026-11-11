package com.campuslife.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3.1 九项功能的「未登录能不能打」闸门测试。
 *
 * 背景：设计稿的测试计划里有 5 处「未登录 → 401」的用例（积分 1 处、段位 1 处、特权 1 处、
 * 论坛榜 1 处、二手我的数据 1 处），此前统统一句「需起服务 curl 验证」挂账。但这件事根本
 * 不需要起服务：鉴权全在 {@link AuthInterceptor#preHandle} 里，用 Mock 的 request/response
 * 直接调它就能证明「不给 token 到底会不会被拦」。
 *
 * 断言口径：
 *   - V3.1 的写接口与「我的数据」读接口，不带 token 一律 401（不是 200，也不是 403）；
 *   - 公开只读接口（榜单、兑换池、认证配置、圈子列表）不能被误伤成 401 —— 这是另一种事故，
 *     拦错会直接把小程序首页打成「请先登录」；
 *   - 管理端/商家端/骑手端路径由各自的拦截器负责，这里必须放行，否则它们全部 401；
 *   - 带合法 token 时正常放行并把 userId 塞进 request attribute（控制器只认这个）。
 *
 * 这个测试不连库、不起 HTTP、不读 Redis，纯对象即可跑。
 */
class V31AuthGateTest {

    private static final String SECRET = "v31-auth-gate-test-secret-0123456789abcdef";
    private static final long USER = 4242L;

    private JwtUtil jwt;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtil(SECRET, 3600);
        // UserStatusGuard 拿不到时跳过「账号是否注销」的校验，这里不需要它
        @SuppressWarnings("unchecked")
        ObjectProvider<com.campuslife.service.UserStatusGuard> provider = Mockito.mock(ObjectProvider.class);
        interceptor = new AuthInterceptor(jwt, provider);
        ReflectionTestUtils.setField(interceptor, "enforce", true);
    }

    // ------------------------------------------------------------------
    // 一、V3.1 写接口：不带 token 必须 401
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V3.1 写接口不带 token 全部 401（签到/兑换/认证/宿舍/二手/建圈）")
    void v31WriteEndpointsRequireToken() throws Exception {
        String[][] writes = {
            {"POST", "/api/points/sign"},                  // F2 签到
            {"POST", "/api/points/redeem"},                // F6 兑换
            {"POST", "/api/verification/apply"},           // F8 提交认证
            {"POST", "/api/dorm"},                         // F9 建宿舍
            {"POST", "/api/dorm/join"},                    // F9 加入宿舍
            {"DELETE", "/api/dorm/leave"},                 // F9 退出宿舍
            {"POST", "/api/market/9/trade"},               // F10 买家发起成交
            {"POST", "/api/market/trade/5/confirm"},       // F10 卖家确认
            {"POST", "/api/market/trade/5/cancel"},        // F10 取消成交
            {"POST", "/api/circles"},                      // F5 建圈（黄金特权）
            {"POST", "/api/circles/3/join"},               // F5 加圈
        };
        for (String[] call : writes) {
            MockHttpServletRequest request = new MockHttpServletRequest(call[0], call[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean passed = interceptor.preHandle(request, response, new Object());

            assertThat(passed).as("%s %s 未登录不该被放行", call[0], call[1]).isFalse();
            assertThat(response.getStatus()).as("%s %s 应为 401", call[0], call[1]).isEqualTo(401);
            assertThat(response.getContentAsString()).as("%s %s 401 响应体", call[0], call[1])
                .contains("\"code\":401");
        }
    }

    // ------------------------------------------------------------------
    // 二、「我的数据」读接口：不带 token 必须 401
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V3.1 我的数据读接口不带 token 全部 401（积分/认证/宿舍/我的成交）")
    void v31PrivateReadsRequireToken() throws Exception {
        String[] reads = {
            "/api/points/me",          // F1 我的积分
            "/api/points/tasks",       // F1 任务进度
            "/api/points/logs",        // F1 积分明细
            "/api/points/rewards",     // F6 兑换池：设计稿五节「权限=登录」（含我的已兑次数）
            "/api/verification/me",    // F8 我的认证状态
            "/api/dorm/me",            // F9 我的宿舍
            "/api/market/trades/mine", // F10 我的发布与成交
        };
        for (String path : reads) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean passed = interceptor.preHandle(request, response, new Object());

            assertThat(passed).as("GET %s 未登录不该被放行", path).isFalse();
            assertThat(response.getStatus()).as("GET %s 应为 401", path).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------------
    // 三、公开只读接口：不能被误伤
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V3.1 公开只读接口不带 token 仍放行（榜单/兑换池/认证配置/圈子）")
    void v31PublicReadsStayOpen() throws Exception {
        String[] reads = {
            "/api/forum/rank",         // F7 榜单：未登录也能看谁上榜
            "/api/verification/config",// F8 认证配置：决定表单渲染哪些字段
            "/api/circles",            // F5 圈子列表（写操作才要特权）
            "/api/circles/1/posts",
        };
        for (String path : reads) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean passed = interceptor.preHandle(request, response, new Object());

            assertThat(passed).as("GET %s 是公开接口，不该被 401", path).isTrue();
            assertThat(response.getStatus()).as("GET %s 不该写 401", path).isNotEqualTo(401);
        }
    }

    // ------------------------------------------------------------------
    // 四、带合法 token：放行并写入身份
    // ------------------------------------------------------------------

    @Test
    @DisplayName("带合法 token 放行，并把 userId 写进 request attribute")
    void validTokenPassesAndExposesUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/points/sign");
        request.addHeader("Authorization", "Bearer " + jwt.issue(USER));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(AuthInterceptor.USER_ID)).isEqualTo(USER);
    }

    @Test
    @DisplayName("X-Token 头的旧约定同样可用")
    void legacyXTokenHeaderStillWorks() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/points/me");
        request.addHeader("X-Token", jwt.issue(USER));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(AuthInterceptor.USER_ID)).isEqualTo(USER);
    }

    @Test
    @DisplayName("伪造/残缺 token 一律当未登录处理")
    void garbageTokenIsTreatedAsAnonymous() throws Exception {
        String[] tokens = {"Bearer not-a-jwt", "Bearer ", "Bearer " + jwt.issue(USER) + "x", ""};
        for (String token : tokens) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/points/sign");
            request.addHeader("Authorization", token);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThat(interceptor.preHandle(request, response, new Object()))
                .as("token=[%s] 不该被当成已登录", token).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------------
    // 五、管理端/商家端/骑手端：这里放行，交给专用拦截器
    // ------------------------------------------------------------------

    @Test
    @DisplayName("管理端/商家端/骑手端路径不由本拦截器判 401")
    void delegatedPathsAreNotJudgedHere() throws Exception {
        String[][] calls = {
            {"POST", "/api/admin/rewards"},
            {"GET", "/api/admin/tier-config"},
            {"POST", "/api/merchant/apply"},
            {"GET", "/api/merchant/orders"},
            {"POST", "/api/rider/tasks/1/accept"},
        };
        for (String[] call : calls) {
            MockHttpServletRequest request = new MockHttpServletRequest(call[0], call[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThat(interceptor.preHandle(request, response, new Object()))
                .as("%s %s 应由专用拦截器负责", call[0], call[1]).isTrue();
            assertThat(response.getStatus()).isNotEqualTo(401);
        }
    }

    // ------------------------------------------------------------------
    // 六、调试开关
    // ------------------------------------------------------------------

    @Test
    @DisplayName("campus.auth.enforce=false 时退回旧行为（仅本机调试，配置校验会告警）")
    void enforceFalseFallsBackToLegacyBehaviour() throws Exception {
        ReflectionTestUtils.setField(interceptor, "enforce", false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/points/sign");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(401);
    }
}