package com.minijudge.problem.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本地开发用全局 CORS 配置（仅服务于"前端静态页跨端口调试"这一件事，不动任何业务）。
 *
 * <p>背景：演示前端 judge-service/src/main/resources/static/index.html 由 8103 端口
 * 的 judge-service 直接托管，而题库读接口与建题/传用例管理接口都在 8102 的
 * problem-service——浏览器同源策略会拦截这条跨端口 fetch，故此处显式放行
 * http://localhost:8103（含 127.0.0.1 等价写法）。</p>
 *
 * <p>为什么生产不需要它：正式部署所有流量统一走网关同一域名，前后端同源，天然
 * 不存在 CORS 问题；8102 端口在真实部署中仅内网可达（见 docker-compose 注释）。
 * 本类是纯开发期适配，网关化后可整体删除。</p>
 *
 * <p>白名单演进：frontend/ 独立静态前端的启动端口不固定（5500/8000，或 file://
 * 打开时的 null Origin），逐列 Origin 不可维护，改为 allowedOriginPatterns("*")。
 * 无 Cookie 会话、身份走 JWT 头，通配来源没有凭据泄露面；8102 本就不对外，
 * 配置随此类一起删除。</p>
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
