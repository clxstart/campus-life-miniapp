package com.campuslife.config;

import com.campuslife.mapper.AdminMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 管理端/骑手令牌的服务端状态校验（finding-03）。
 *
 * JWT 只证明「签发时」的身份；每个请求再按主键查一次 admin_user，要求：
 *   - 账号存在且 status=1；
 *   - 令牌里的 tv（token_version）与库里一致——停用、重新启用、重置密码都会让版本 +1；
 *   - 令牌中的角色、学校、店铺与当前库中一致——权限被调整后旧令牌不能继续沿用旧范围。
 * 数据库查询失败时返回 UNAVAILABLE，由拦截器拒绝请求（503），绝不放行。
 *
 * 失效窗口：即时（无缓存）。代价是每个管理端请求一次主键查询，管理端 QPS 很低，可接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminTokenGuard {
    public enum Verdict { OK, REVOKED, UNAVAILABLE }

    /** 校验通过时返回库中的当前账号状态，供拦截器写入请求属性 */
    public record Result(Verdict verdict, Map<String, Object> account) {}

    private final AdminMapper mapper;

    public Result check(Claims claims) {
        Long id;
        try { id = Long.valueOf(claims.getSubject()); }
        catch (RuntimeException e) { return new Result(Verdict.REVOKED, null); }
        Map<String, Object> account;
        try { account = mapper.tokenState(id); }
        catch (RuntimeException e) {
            log.warn("[admin-auth] 令牌状态查询失败，拒绝请求：{}", e.getClass().getSimpleName());
            return new Result(Verdict.UNAVAILABLE, null);
        }
        if (account == null || number(account.get("status")) != 1) return new Result(Verdict.REVOKED, null);
        // 旧令牌没有 tv 声明时按 0 处理：迁移后首次停用/改权限即失效
        long tokenVersion = claims.get("tv") instanceof Number n ? n.longValue() : 0L;
        if (tokenVersion != number(account.get("tokenVersion"))) return new Result(Verdict.REVOKED, null);
        if (!Objects.equals(claims.get("role", String.class), account.get("role"))) return new Result(Verdict.REVOKED, null);
        if (!sameId(claims.get("schoolId"), account.get("schoolId"))) return new Result(Verdict.REVOKED, null);
        if (!sameId(claims.get("shopId"), account.get("shopId"))) return new Result(Verdict.REVOKED, null);
        return new Result(Verdict.OK, account);
    }

    /** 一次性初始密码登录后，除改密与退出外禁止访问业务接口；必须由服务端拦截，不能只靠前端跳转。 */
    public static boolean mustChangePassword(Map<String, Object> account) {
        return number(account == null ? null : account.get("mustChangePassword")) == 1;
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : -1L; }

    /** null 与 0/缺省视为「未绑定」，其余按数值比较 */
    private static boolean sameId(Object claim, Object current) {
        long a = claim instanceof Number n ? n.longValue() : 0L;
        long b = current instanceof Number n ? n.longValue() : 0L;
        return a == b;
    }
}
