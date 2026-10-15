package com.minijudge.judge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本地开发用全局 CORS 配置（与 user/problem 两侧同名配置同一取舍，仅服务于
 * "独立前端跨端口直连调试"这一件事，不动任何业务）。
 *
 * <p>背景：frontend/ 静态页默认经网关 8085 访问全部服务（网关已有 globalcors 兜底）；
 * README 同时提供"绕过网关直连 8101/8102/8103"的调试模式——此时浏览器从任意本地
 * 端口（5500/8000，或 file:// 的 null Origin）向 8103 发起跨端口 fetch，需要这里放行。
 * 直连模式下 X-User-Id 由前端手工携带（网关的 JWT 校验与身份覆盖不参与），这正是
 * "为什么生产必须过网关"的反面教材：谁都能在浏览器里冒充任意用户。</p>
 *
 * <p>为什么生产不需要它：正式部署流量统一走网关同域反代，8103 端口仅内网可达；
 * 本类是纯开发期适配，网关化后可整体删除。</p>
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
