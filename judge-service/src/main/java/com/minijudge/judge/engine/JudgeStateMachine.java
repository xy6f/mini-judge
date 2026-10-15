package com.minijudge.judge.engine;

import java.util.Map;
import java.util.Set;

/**
 * 提交状态机（计划书第 4 章：QUEUED -> RUNNING -> DONE，异常路径 REQUEUE）。
 *
 * <p>独立成纯逻辑类的目的（面试点）：状态流转是评测引擎的"正确性内核"，
 * 它必须不依赖 Spring/DB 就能被单测穷举——所有非法流转（如 DONE -> RUNNING、
 * QUEUED -> DONE 跳步）都要在写库之前被拦截，而不是靠 SQL 的 WHERE 条件兜底后
 * 才发现。Dispatcher 的 CAS、worker 的落库守卫、watchdog 的超时重置，
 * 三处并发写方共享这同一张"合法边表"，改状态机只改这一个文件。</p>
 */
public final class JudgeStateMachine {

    /** 状态常量（与 t_submission.status 列取值一致） */
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String REQUEUE = "REQUEUE";

    /**
     * 合法转移边表：
     * QUEUED -> RUNNING   ：Dispatcher CAS 领取（claim SQL 已守卫）
     * QUEUED -> REQUEUE   ：排队中被管理端 rejudge（等下一轮恢复）
     * RUNNING -> DONE     ：worker 判分完成（写库前再校验一次）
     * RUNNING -> REQUEUE  ：中断/评测器故障/看门狗认定执行者丢失
     * REQUEUE -> QUEUED   ：恢复轮把它重新放回可领取集合
     * DONE -> REQUEUE     ：管理端 rejudge 已完成提交（保留历史 result 供对比）
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            QUEUED, Set.of(RUNNING, REQUEUE),
            RUNNING, Set.of(DONE, REQUEUE),
            REQUEUE, Set.of(QUEUED),
            DONE, Set.of(REQUEUE)
    );

    private JudgeStateMachine() {}

    /**
     * 是否合法转移；未知状态名一律 false（白名单思路：不认识=不允许）。
     * 显式 null 守卫：Map.of/Set.of 的不可变集合对 null 键/参数直接抛 NPE，
     * 状态机作为公共断言入口，宁可把 null 判成"非法转移"也不让它炸穿调用方。
     */
    public static boolean canTransition(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        Set<String> outs = TRANSITIONS.get(from);
        return outs != null && outs.contains(to);
    }

    /** 断言式版本：非法流转抛 IllegalStateException，消息里带两个状态便于排查 */
    public static void assertTransition(String from, String to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "illegal submission transition: " + from + " -> " + to);
        }
    }
}
