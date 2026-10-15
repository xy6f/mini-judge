package com.minijudge.judge.engine;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.minijudge.judge.entity.Submission;
import com.minijudge.judge.mapper.SubmissionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评测调度器：DB 队列表的消费端（计划书 7.2）。
 *
 * <p>节奏设计（面试可讲的三个细节）：
 * 1. fixedDelay 而非 fixedRate——评测任务耗时远超间隔，防任务堆积重入；
 * 2. 先抢信号量再领取任务——保证"领了就一定有人执行"，不会领完排队饿死；
 * 3. CAS 领取失败直接换下一个候选——多 worker 并行消费天然安全，
 *    未来评测机横向扩容只需多起几个 judge-service 实例（无额外协调）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeDispatcher {

    private final SubmissionMapper submissionMapper;
    private final JudgeWorkerPool workerPool;

    /**
     * 启动恢复（计划书 7.3）：进程崩溃/重启会把 RUNNING 留成孤儿——
     * 全部重置为 REQUEUE 等待重新入队。判分天然幂等（同源码同用例同结果），
     * 所以"重跑无害"是本设计成立的前提，面试标准答案。
     */
    @PostConstruct
    public void recoverOrphans() {
        // RUNNING -> REQUEUE
        int n = submissionMapper.resetRunning();
        // REQUEUE -> QUEUED（重新参与领取）
        int m = submissionMapper.update(null,
                Wrappers.<Submission>lambdaUpdate()
                        .set(Submission::getStatus, "QUEUED")
                        .eq(Submission::getStatus, "REQUEUE"));
        if (n + m > 0) {
            log.warn("startup recovery: {} running->requeue, {} requeued", n, m);
        }
    }

    /** 每秒尝试装满空闲 worker */
    @Scheduled(fixedDelay = 1000, initialDelay = 3000)
    public void dispatchRound() {
        // 运行期恢复轮：W1 只在启动时做 REQUEUE->QUEUED，导致 rejudge/看门狗置
        // REQUEUE 的任务要等重启才能重进队列。这里每轮开头补一次条件更新，让
        // "重判""丢失执行者恢复"运行时即生效（走 idx_status_submit 索引，空表时近似零成本）。
        // 已知边界（多实例部署需再演进）：看门狗置 REQUEUE 时原 worker 可能还活着，
        // 存在"双跑"窗口——worker 写终态带 WHERE status='RUNNING' CAS 守卫，
        // 迟到方会作废自己的结果，判分幂等所以结果无害；生产化方案见计划书 8.2 MQ 演进。
        submissionMapper.requeueToQueued();
        while (workerPool.available()) {
            Submission taken = takeOne();
            if (taken == null) {
                workerPool.releaseUnusedPermit();   // 没任务要还 permit，否则池子被"空占"
                return;
            }
            // 领取成功即投入异步执行：调度线程只负责"领"，不干重活
            workerPool.submit(taken);
        }
    }

    /** 领取一条：查候选 -> CAS 抢占，最多试 5 个候选（都被抢就本轮放弃） */
    private Submission takeOne() {
        List<Long> candidates = submissionMapper.selectQueuedIds(5);
        for (Long id : candidates) {
            if (submissionMapper.claim(id) == 1) {
                Submission s = submissionMapper.selectById(id);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }
}
