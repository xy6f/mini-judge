package com.minijudge.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * local profile 专属的"用例路由拒绝"演示过滤器（计划书 10 章"用例泄露"的网关侧落地）。
 *
 * <p>要拦截的路径与方法：仅 GET /api/problems/{id}/cases —— 该读端点返回全部
 * 测试用例的文件路径元信息（含非样例），属于敏感外泄面：外部用户拿到用例
 * 等于拿到答案。</p>
 *
 * <p>为什么放行 POST/PUT 而同为 cases 路由却只拦 GET（威胁模型差异，面试要点）：
 * 同一 URL 的两种方法安全语义完全不同——GET 是"数据流出"（泄露通道），
 * POST/PUT 是"数据流入"（管理端建题传用例，计划书第 6 章 ADMIN 职能）。
 * 写路径的管控手段是 RBAC（网关对写方法断言 ADMIN 角色，见生产策略注释），
 * 而不是 deny 整条路由——若连 POST 一起拦死，管理端经网关传用例的正常
 * 链路就断了，属于"为堵一扇窗把整面墙砌死"。</p>
 *
 * <p>生产策略（本 filter 仅 local 演示件，真实的网关层方案二选一）：
 * 1. 路由级：Spring Cloud Gateway 对 /api/problems/{id}/cases 的 GET 方法配
 *    default-filters 直接 403（声明式规则，无需 Java 代码）；
 * 2. RBAC 级：JWT 里带 role，网关按"写方法要求 ADMIN、cases 读路由全部
 *    deny 外部"两条规则收口；服务间拉用例走 Nacos 内网直连不过网关。</p>
 *
 * <p>为什么做成 @Profile("local") 的演示件（面试要点——护栏分层的完整答案）：
 * 1. 生产环境的拦截不是靠这段 Java 代码，而是"网关 deny 规则 + 网络隔离"双保险：
 *    problem-service 的 8102 端口根本不对外，只在内网；judge-service 经 Feign
 *    走服务发现内网直连拉用例，流量根本不过网关，想拦都拦不到内网调用；
 * 2. 冒烟环境（local profile）三个服务都开在 localhost 上，没有网络隔离可言，
 *    于是用网关 GlobalFilter 在应用层复现"外部访问 cases 路由 -> 403"的行为，
 *    验证的是网关这条路确实堵死；内网直连 8102 的评测链路不受影响（不经网关）。</p>
 *
 * <p>开关：mini-judge.gateway.deny-cases-route=false 时可临时放行，
 * 用于对比演示"不拦截会发生什么"。</p>
 */
@Component
@org.springframework.context.annotation.Profile("local")
public class LocalCaseDenyFilter implements GlobalFilter, Ordered {

    /** /api/problems/{数字id}/cases 的精确形状——只拦这一个敏感端点，不误伤题面/题单 */
    private static final Pattern CASES_PATH =
            Pattern.compile("^/api/problems/\\d+/cases/?$");

    @Value("${mini-judge.gateway.deny-cases-route:true}")
    private boolean denyEnabled;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        // 读方法（GET 取数据、HEAD 探测数据存在性）才是泄露通道；写方法放行给 RBAC 管
        boolean readMethod = exchange.getRequest().getMethod() == HttpMethod.GET
                || exchange.getRequest().getMethod() == HttpMethod.HEAD;
        if (denyEnabled && readMethod && CASES_PATH.matcher(path).matches()) {
            // 403 而非 404：403 明确"你有身份但无权限"，404 会误导调试；
            // 直接 setComplete 短路，不再进路由转发链
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    /**
     * order 取 -50：在 JwtGlobalFilter(-100) 之后执行——先确认"你是谁"再决定拒绝，
     * 未登录者仍得 401，已登录者得 403，语义分层与生产一致。
     */
    @Override
    public int getOrder() {
        return -50;
    }
}
