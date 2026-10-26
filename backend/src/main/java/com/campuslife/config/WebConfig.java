package com.campuslife.config;

import com.campuslife.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final RiderInterceptor riderInterceptor;
    private final UploadService uploadService;

    /** 管理端跨域白名单，来自 campus.web.allowed-origins（逗号分隔）。 */
    @Value("${campus.web.allowed-origins:http://127.0.0.1:*,http://localhost:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toArray(String[]::new);
        registry.addMapping("/api/**")
            .allowedOriginPatterns(origins.length == 0 ? new String[] {"http://127.0.0.1:*", "http://localhost:*"} : origins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS").allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 小程序端：/api/** 全部经过这里。公开接口放行匿名访问，
        // 写操作与「我的数据」接口必须带有效 token（campus.auth.enforce 可临时关闭）。
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**").excludePathPatterns("/api/pay/notify");
        // 管理端 + 商家端：必须携带有效 admin token（商家身份由 token 里的 role/shopId 判定）
        registry.addInterceptor(adminInterceptor).addPathPatterns("/api/admin/**", "/api/merchant/**");
        // 骑手端：单独的拦截器（task-15），只放行 role=rider 的 token
        registry.addInterceptor(riderInterceptor).addPathPatterns("/api/rider/**");
    }

    // 上传的图片通过 /uploads/** 直接访问
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploadService.root().toUri().toString());
    }
}
