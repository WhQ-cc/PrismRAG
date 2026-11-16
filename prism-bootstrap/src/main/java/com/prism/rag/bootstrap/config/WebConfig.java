package com.prism.rag.bootstrap.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：跨域。
 *
 * <p>开发期前端如果跑在 Vite（5173 端口），直接调 8080 会被浏览器同源策略拦下。
 * 这里放开跨域，方便前后端分离开发。</p>
 *
 * <p><b>生产环境不要用 {@code addAllowedOrigin("*")}：</b>
 * 允许任意来源会让 CSRF 攻击变得容易。生产应该配具体的域名，
 * 并且配合认证（Sa-Token / Spring Security）。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
