package com.minijudge.user.controller;

import com.minijudge.common.Result;
import com.minijudge.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口。路径 /api/auth/** 是网关白名单前缀，无需令牌即可访问（计划书第 6 章）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody AuthService.AccountReq req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody AuthService.AccountReq req) {
        return authService.login(req);
    }
}
