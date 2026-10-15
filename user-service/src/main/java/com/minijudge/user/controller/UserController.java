package com.minijudge.user.controller;

import com.minijudge.common.Result;
import com.minijudge.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户查询接口（RBAC 收口新增）。
 *
 * <p>GET /api/users/{id}/role 首要消费者是 judge-service（rejudge 前服务端
 * 强校验角色，经 Feign 内网直连调用，不过网关）；经网关访问时该路径同样
 * 要求携带合法 JWT（网关白名单只有 /api/auth/**），只读自身可查、不泄露敏感列，
 * 风险可控。刻意做成"按 id 查角色"的只读窄接口，而不是通用用户查询——
 * 接口面积越小，越不容易变成横向探测的入口。</p>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    /** 查单个用户的角色（USER/ADMIN）。无角色数据按 USER 返回（最小权限兜底） */
    @GetMapping("/{id}/role")
    public Result<String> role(@PathVariable Long id) {
        return authService.roleOf(id);
    }
}
