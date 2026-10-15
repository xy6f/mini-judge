package com.minijudge.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本地开发用全局 CORS 配置（仅服务于"前端静态页跨端口调试"这一件事，不动任何业务）。
 *
 * <p>背景：演示前端 judge-service/src/main/resources/static/index.html 由 8103 端口
 * 的 judge-service 直接托管，而注册登录接口在 8101 的 user-service——浏览器同源策略
 * 会拦截这条跨端口 fetch，故此处显式放行 http://localhost:8103（含 127.0.0.1 等价写法）。</p>
 *
 * <p>为什么生产不需要它：正式部署所有流量统一走网关（8080/8085）同一域名，前后端
 * 同源，天然不存在 CORS 问题；各微服务端口（8101/8102/8103）在内网根本不对外暴露。
 * 把 CORS 白名单写死到配置类而不是配置文件，是有意为之的"只在本机冒烟生效"信号——
 * 生产网关化后此类可整体删除，不留下配置漂移的隐患。</p>
 *
 * <p>白名单演进：早期只放行 8103（前端页面恰好由 judge-service 托管、Origin 固定）；
 * 现在 frontend/ 是独立静态目录，启动端口由使用者自定（5500/8000/file:// 的 null
 * Origin 都可能），逐个列 Origin 不可维护，故改为 allowedOriginPatterns("*")。
 * 本服务无 Cookie 会话（身份走 JWT 头），通配来源没有 CSRF/凭据泄露面；即便如此
 * 它仍然只在本机冒烟有意义——生产这些端口不对网暴露，配置随此类一起删除。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
