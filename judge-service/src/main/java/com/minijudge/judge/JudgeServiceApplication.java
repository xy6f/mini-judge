package com.minijudge.judge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 评测服务入口。
 *
 * <p>@EnableScheduling：Dispatcher 轮询队列表；
 * @EnableFeignClients：ProblemClient 按服务名 problem-service 经 Nacos 取实例。</p>
 */
@SpringBootApplication
@MapperScan("com.minijudge.judge.mapper")
@EnableFeignClients
@EnableScheduling
public class JudgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JudgeServiceApplication.class, args);
    }
}
