package com.minijudge.gateway.filter;

import com.minijudge.common.JwtSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局 JWT 鉴权过滤器：微服务鉴权收敛到网关的标准实现。
 *
 * <p>流程：/api/auth/** 直接放行（注册/登录匿名）；其余路径必须携带合法
 * Bearer 令牌，解析成功后剥离客户端伪造的 X-User-Id、重新注入网关签认的值，
 * 下游服务只读头不再解析令牌。</p>
 *
 * <p>安全细节：mutate 前先 headers(h -> h.remove(...))，防止外部请求自带
 * X-User-Id 冒名——"永远覆盖、而不是仅在缺失时添加"。</p>
 */
@Component
public class JwtGlobalFilter implements GlobalFilter, Ordered {

    /** 鉴权白名单：仅登录注册类 */
    private static final String AUTH_PREFIX = "/api/auth/";

    @Value("${mini-judge.jwt.secret}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getURI().getPath();

        // 1. 白名单放行
        if (path.startsWith(AUTH_PREFIX)) {
            return chain.filter(exchange);
        }

        // 2. 取令牌并解析
        String authHeader = req.getHeaders().getFirst("Authorization");
        JwtSupport.TokenUser user = (authHeader != null && authHeader.startsWith("Bearer "))
                ? JwtSupport.parse(jwtSecret, authHeader.substring(7))
                : null;
        if (user == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 3. 覆盖注入用户身份头，转发下游
        // X-User-Role 仅作"展示级"透传（前端/下游日志可读当前角色），旧令牌无
        // role claim 时 roleOrUser() 兜底 USER——向后兼容。
        // 重要：下游的授权判定不读这个头！令牌角色是签发时刻的快照，撤权不会
        // 让旧令牌失效；rejudge/建题的 ADMIN 校验各自现查 user-service/t_user
        //（服务端强校验），此头只是减少前端一次查询的展示优化。
        ServerHttpRequest mutated = req.mutate()
                .headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-User-Role");   // 与 X-User-Id 同规则：永远覆盖，防客户端伪造
                    h.add("X-User-Id", String.valueOf(user.userId()));
                    h.add("X-User-Role", user.roleOrUser());
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** 负数 order：排在网关内置过滤器（路由等）之前先做安全 */
    @Override
    public int getOrder() {
        return -100;
    }
}
