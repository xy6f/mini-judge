package com.minijudge.judge.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.minijudge.judge.engine.JudgeStateMachine.DONE;
import static com.minijudge.judge.engine.JudgeStateMachine.QUEUED;
import static com.minijudge.judge.engine.JudgeStateMachine.REQUEUE;
import static com.minijudge.judge.engine.JudgeStateMachine.RUNNING;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机纯逻辑单测：合法边全放行、非法边全拦截（含跳步、回退、终态篡改），
 * 外加"未知状态名白名单拒绝"。这是评测引擎正确性内核的最便宜防线——
 * Dispatcher/worker/watchdog 三个并发写方共用它，行为在这里一次性锁死。
 */
class JudgeStateMachineTest {

    @Test
    @DisplayName("五条合法转移边全部放行")
    void legalTransitions() {
        assertTrue(JudgeStateMachine.canTransition(QUEUED, RUNNING));    // 领取
        assertTrue(JudgeStateMachine.canTransition(RUNNING, DONE));      // 判完
        assertTrue(JudgeStateMachine.canTransition(RUNNING, REQUEUE));   // 故障/看门狗
        assertTrue(JudgeStateMachine.canTransition(REQUEUE, QUEUED));    // 恢复重入队
        assertTrue(JudgeStateMachine.canTransition(DONE, REQUEUE));      // 管理端 rejudge
        assertTrue(JudgeStateMachine.canTransition(QUEUED, REQUEUE));    // 排队中被 rejudge
    }

    @Test
    @DisplayName("非法转移全部拦截：跳步/回退/自我转移/终态直入")
    void illegalTransitions() {
        assertFalse(JudgeStateMachine.canTransition(QUEUED, DONE));       // 没评测就出结果？
        assertFalse(JudgeStateMachine.canTransition(QUEUED, QUEUED));
        assertFalse(JudgeStateMachine.canTransition(RUNNING, QUEUED));    // 不许回退
        assertFalse(JudgeStateMachine.canTransition(DONE, RUNNING));      // 终态不能被重新领取
        assertFalse(JudgeStateMachine.canTransition(DONE, DONE));
        assertFalse(JudgeStateMachine.canTransition(REQUEUE, RUNNING));   // 必须先回 QUEUED
        assertFalse(JudgeStateMachine.canTransition(REQUEUE, DONE));
    }

    @Test
    @DisplayName("未知状态名一律拒绝（白名单思路）")
    void unknownStatesRejected() {
        assertFalse(JudgeStateMachine.canTransition("QUEUD", RUNNING));   // 拼写错也不放过
        assertFalse(JudgeStateMachine.canTransition(RUNNING, null));
        assertFalse(JudgeStateMachine.canTransition(null, DONE));
    }

    @Test
    @DisplayName("assertTransition：非法即抛且消息带两个状态便于定位")
    void assertThrowsOnIllegal() {
        JudgeStateMachine.assertTransition(QUEUED, RUNNING);   // 合法不应抛
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JudgeStateMachine.assertTransition(DONE, RUNNING));
        assertTrue(e.getMessage().contains("DONE") && e.getMessage().contains("RUNNING"));
    }
}
