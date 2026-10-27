package com.campuslife.config;

import com.campuslife.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 判断提示是否已经是中文（是的话原样返回，尊重 DTO 里手写的文案）。 */
    private static final Pattern HAS_CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    /**
     * 字段名 → 中文标签。
     * 作用：Bean Validation 的默认提示是英文（"must not be null"），直接透出去小程序会看到英文；
     * 这里按字段名翻译成「内容不能为空」这种可直接展示的话。
     * 以后新增 DTO 忘了写 message 也会被这层兜住。
     */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
        Map.entry("userId", "用户"), Map.entry("title", "标题"), Map.entry("content", "内容"),
        Map.entry("nickname", "昵称"), Map.entry("postId", "帖子"), Map.entry("clientId", "消息编号"),
        Map.entry("circleId", "圈子"), Map.entry("activityId", "活动"), Map.entry("goodsId", "商品"),
        Map.entry("shopId", "店铺"), Map.entry("name", "名称"), Map.entry("gender", "性别"),
        Map.entry("photo", "照片"), Map.entry("detail", "地址详情"), Map.entry("phone", "电话"),
        Map.entry("contact", "联系人"), Map.entry("image", "图片"), Map.entry("tag", "标签"),
        Map.entry("location", "地点"), Map.entry("price", "价格"), Map.entry("count", "数量"),
        Map.entry("items", "商品清单"), Map.entry("address", "收货地址"), Map.entry("reason", "原因"),
        Map.entry("materials", "报名材料"), Map.entry("realName", "真实姓名"), Map.entry("enrollMode", "报名方式")
    );

    @ExceptionHandler(AuditRejectedException.class)
    public ResponseEntity<ApiResponse<Object>> auditRejected(AuditRejectedException e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(4001, e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> valid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError == null ? "参数错误" : translate(fieldError);
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, msg, null));
    }

    /** 把校验错误翻成中文：已写中文则原样用，否则按「字段标签 + 错误类型」拼一句。 */
    private static String translate(FieldError error) {
        String raw = error.getDefaultMessage();
        if (raw != null && HAS_CHINESE.matcher(raw).find()) return raw;

        String label = FIELD_LABELS.getOrDefault(error.getField(), error.getField());
        String code = error.getCode() == null ? "" : error.getCode();
        return switch (code) {
            case "NotNull", "NotBlank", "NotEmpty" -> label + "不能为空";
            case "Size" -> label + "长度不合法";
            case "Min" -> label + "小于允许值";
            case "Max" -> label + "超过允许值";
            case "Positive" -> label + "必须大于 0";
            case "Pattern", "Email" -> label + "格式不正确";
            default -> label + "参数不合法";
        };
    }

    // 权限不足：403（与参数错误 400 区分开，前端据此跳登录或提示无权限）
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Object>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(403).body(new ApiResponse<>(403, e.getMessage() == null ? "无权限" : e.getMessage(), null));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Object>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(401).body(new ApiResponse<>(401, e.getMessage() == null ? "请先登录" : e.getMessage(), null));
    }

    // 请求过于频繁：429（上传频率限制等）
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Object>> tooMany(TooManyRequestsException e) {
        return ResponseEntity.status(429).body(new ApiResponse<>(429, e.getMessage() == null ? "请求过于频繁" : e.getMessage(), null));
    }

    // 业务冲突：409（如重复报名、状态不允许）
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(new ApiResponse<>(409, e.getMessage() == null ? "操作冲突" : e.getMessage(), null));    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Object>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, readable(e), null));
    }

    /**
     * 把框架抛出的英文异常转成中文提示。
     * 只翻这几种能预判的；业务代码自己抛的 IllegalArgumentException 本来就是中文，原样返回。
     */
    private static String readable(Exception e) {
        String raw = e.getMessage();
        if (raw == null || raw.isBlank()) return "参数错误";
        if (HAS_CHINESE.matcher(raw).find()) return raw;   // 业务自抛的中文，直接用

        if (e instanceof org.springframework.web.bind.MissingServletRequestParameterException missing) {
            String label = FIELD_LABELS.getOrDefault(missing.getParameterName(), missing.getParameterName());
            return "缺少必需参数：" + label;
        }
        if (e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException mismatch) {
            String label = FIELD_LABELS.getOrDefault(mismatch.getName(), mismatch.getName());
            return label + "格式不正确";
        }
        if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return "请求体格式不正确";
        }
        // 兜底：不要把英文原文透给用户
        return "参数不合法";
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "图片不能超过 5MB", null));
    }

    // 上传相关：不是 multipart、缺文件字段 —— 属于客户端用法错误，应该是 400 而不是 500
    @ExceptionHandler({
        org.springframework.web.multipart.MultipartException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class
    })
    public ResponseEntity<ApiResponse<Object>> multipart(Exception e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请选择要上传的文件", null));
    }

    // 缺必需请求头（如微信支付回调的签名头）
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> missingHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "缺少必需请求头：" + e.getHeaderName(), null));
    }

    // 兜底：对外只说「服务器错误」，具体堆栈只进日志，不把内部异常原文透给用户
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> error(Exception e) {
        log.error("未处理的服务端异常", e);
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "服务器繁忙，请稍后再试", null));
    }
}
