package com.minijudge.judge.client;

import com.minijudge.common.Result;
import com.minijudge.judge.client.dto.CaseInfo;
import com.minijudge.judge.client.dto.ProblemInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 声明式调用 problem-service（Feign 按服务名发现，URL 由 Nacos 决定）。
 *
 * <p>面试点：Feign 本质=动态代理 + Ribbon/LoadBalancer 选实例 + HTTP；
 * 这里 judge->problem 走服务发现而非网关，服务间流量不过网关是常见架构约束。</p>
 *
 * <p>环境无关化：url 属性配成占位符——基线环境该属性为空，Feign 忽略 url
 * 继续按 name 走"服务发现+负载均衡"；local profile 填 http://localhost:8102
 * 则直连固定地址、无需 Nacos。一份接口注解兼容两种部署形态。</p>
 */
@FeignClient(name = "problem-service",
        url = "${mini-judge.problem-service.url:}",
        path = "/api/problems")
public interface ProblemClient {

    @GetMapping("/{id}")
    Result<ProblemInfo> getProblem(@PathVariable("id") Long id);

    /** 拉全部用例元信息（含非样例），只供内网评测使用 */
    @GetMapping("/{id}/cases")
    Result<List<CaseInfo>> getCases(@PathVariable("id") Long id);
}
