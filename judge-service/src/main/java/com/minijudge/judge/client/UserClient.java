package com.minijudge.judge.client;

import com.minijudge.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 声明式调用 user-service 的角色查询接口（RBAC 收口）。
 *
 * <p>为什么 rejudge 不直接信 JWT/网关头里的角色：令牌是 720 分钟的快照，
 * 角色撤了旧令牌还在——所以每次管理操作现查 user-service（数据源头是 t_user），
 * 撤权即时生效。网关注入的 X-User-Role 只作展示透传，不参与这里的判定。</p>
 *
 * <p>url 占位符模式与 ProblemClient 一致：基线留空走 Nacos 服务发现，
 * local profile 填 http://localhost:8101 直连。</p>
 */
@FeignClient(name = "user-service",
        url = "${mini-judge.user-service.url:}",
        path = "/api/users")
public interface UserClient {

    /** 查用户角色：成功时 data 为 USER/ADMIN；无角色数据由 user-service 兜底成 USER */
    @GetMapping("/{id}/role")
    Result<String> getRole(@PathVariable("id") Long userId);
}
