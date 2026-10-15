package com.minijudge.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务入口：注册到 Nacos（spring.application.name=user-service，
 * 网关路由里的 lb://user-service 就是按这个名字发现的）。
 */
@SpringBootApplication
@MapperScan("com.minijudge.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
