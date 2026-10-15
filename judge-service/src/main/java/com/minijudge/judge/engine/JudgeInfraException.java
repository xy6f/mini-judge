package com.minijudge.judge.engine;

/**
 * 评测器自身故障异常（公正性设计，计划书 3.2）。
 *
 * <p>关键区分（面试点）："用户代码错了" 与 "评测系统自己出问题了" 必须走不同通道：
 * 前者判 WA/TLE/RE/CE 终态，后者绝不能冤枉用户——worker 捕获本异常后把任务置
 * REQUEUE 重跑（判分幂等，重跑无害），而不是给出任何面向用户的判定码。
 * 典型来源：Feign 拉题/拉用例失败、用例文件缺失、沙箱工具链本身报错等。</p>
 */
public class JudgeInfraException extends RuntimeException {

    public JudgeInfraException(String message) {
        super(message);
    }

    public JudgeInfraException(String message, Throwable cause) {
        super(message, cause);
    }
}
