package com.minijudge.judge.engine;

import com.minijudge.judge.entity.JudgeLog;
import com.minijudge.judge.mapper.JudgeLogMapper;
import com.minijudge.judge.mapper.SubmissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评测看门狗（计划书 7.2"每任务独立看门狗"的服务级实现）。
 *
 * <p>职责：定时扫描 RUNNING 且开始评测已超过 N 分钟（默认 5）的提交——正常任务
 * 有 simulate/docker 的进程级超时强杀，一个任务能" RUNNING 超 5 分钟"只可能是
 * 执行者丢失（worker 线程被 OOM 杀掉、docker 守护挂死、进程假死），此时把它置
 * REQUEUE 重新入队重跑。这是比"单任务超时"更高一层的兜底：超时杀的是用户进程，
 * 看门狗防的是"评测系统自己丢了任务"，两层缺一不可（面试标准答法）。</p>
 *
 * <p>计时基准：claim 时写入的 judged_time（=开始评测时刻，见 SubmissionMapper
 * 注释），不是 submit_time——防"排队久被误杀"。</p>
 *
 * <p>与 worker 的竞争：重置走 WHERE status='RUNNING' 的条件 CAS，任务恰好
 * 在扫描瞬间自然完成则不碰它；worker 稍后迟到的结果也会被自己那边的 CAS 守卫
 * 作废。REQUEUE 由 Dispatcher 每轮转回 QUEUED 重新评测（判分幂等，重跑无害）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeWatchdog {

    private final SubmissionMapper submissionMapper;
    private final JudgeLogMapper judgeLogMapper;

    /** 超时阈值分钟数：远大于 docker 模式宿主兜底超时（单用例时限*5+30s），确保"抢不过"进程级强杀 */
    @Value("${mini-judge.judge.watchdog-timeout-minutes:5}")
    private int timeoutMinutes = 5;

    /** 每分钟扫一轮：任务级超时有进程waitFor兜着，这里只抓"执行者已丢"的漏网之鱼，低频足够 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sweep() {
        List<Long> timedOut = submissionMapper.selectRunningTimedOut(timeoutMinutes);
        for (Long id : timedOut) {
            if (submissionMapper.requeueRunning(id) == 1) {
                log.warn("watchdog: submission {} RUNNING over {}min, executor presumed lost -> REQUEUE",
                        id, timeoutMinutes);
                try {
                    JudgeLog jl = new JudgeLog();
                    jl.setSubmissionId(id);
                    jl.setEvent("WATCHDOG");
                    jl.setDetail("running over " + timeoutMinutes + "min -> REQUEUE");
                    jl.setCreatedAt(LocalDateTime.now());
                    judgeLogMapper.insert(jl);
                } catch (Exception e) {
                    log.warn("watchdog judge_log failed sub={}", id, e);
                }
            }
        }
    }
}
