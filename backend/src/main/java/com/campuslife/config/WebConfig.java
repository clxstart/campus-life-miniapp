package com.campuslife.config;
import com.campuslife.service.UploadService; import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration; import org.springframework.web.servlet.config.annotation.*;
@Configuration @RequiredArgsConstructor public class WebConfig implements WebMvcConfigurer {
 private final AuthInterceptor authInterceptor; private final AdminInterceptor adminInterceptor; private final RiderInterceptor riderInterceptor; private final UploadService uploadService;
 @Override public void addCorsMappings(CorsRegistry r){
   r.addMapping("/api/**").allowedOriginPatterns("http://127.0.0.1:*","http://localhost:*")
    .allowedMethods("GET","POST","PUT","DELETE","OPTIONS").allowedHeaders("*");
 }
 @Override public void addInterceptors(InterceptorRegistry registry){
   // 小程序端：解析失败不阻断，由接口自行降级（保证无后端也能演示）
   registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
   // 管理端：必须携带有效 token，否则 401。顺序在用户拦截器之后，互不影响。
   registry.addInterceptor(adminInterceptor).addPathPatterns("/api/admin/**");
   registry.addInterceptor(riderInterceptor).addPathPatterns("/api/rider/**");
 }
 // 上传的图片通过 /uploads/** 直接访问
 @Override public void addResourceHandlers(ResourceHandlerRegistry registry){
   registry.addResourceHandler("/uploads/**").addResourceLocations(uploadService.root().toUri().toString());
 }
}
