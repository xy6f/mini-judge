package com.minijudge.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minijudge.judge.entity.Submission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 提交 Mapper。核心是两个手写 SQL：队列表"原子抢任务"。
 *
 * <p>claimOne 用 CAS（UPDATE ... WHERE status='QUEUED' LIMIT 1 再按 ID 回查不行，
 * 所以换思路：先按 id 升序 LIMIT 1 查候选，再 CAS 更新——更新影响行数 0 表示被
 * 其他 worker 抢走，换下一个候选重试。对比 SELECT FOR UPDATE SKIP LOCKED：
 * CAS 无锁等待、天然防"取到但处理者崩了锁悬挂"，单机 sqlite/mysql 都好演示。</p>
 */
public interface SubmissionMapper extends BaseMapper<Submission> {

    /** 最早的 QUEUED 候选（按提交时间 FIFO，避免饿死） */
    @Select("SELECT id FROM t_submission WHERE status = 'QUEUED' ORDER BY submit_time LIMIT #{limit}")
    List<Long> selectQueuedIds(int limit);

    /**
     * 原子领取：QUEUED -> RUNNING；返回 1 表示抢到。
     * 顺手把 judged_time 置为 NOW()：该列语义定为"最后一次状态转移时刻"
     * （RUNNING 时=评测开始时间，DONE 时=判定完成时间）——看门狗据此度量
     * "RUNNING 已持续多久"，若用 submit_time，一个排队 10 分钟才被领到的任务
     * 会被看门狗误判成"跑了 10 分钟"立刻强杀，这是队列型系统的经典计时基准坑。
     */
    @Update("UPDATE t_submission SET status = 'RUNNING', judged_time = NOW() " +
            "WHERE id = #{id} AND status = 'QUEUED'")
    int claim(@Param("id") Long id);

    /** 孤儿任务恢复用：把非本机的 RUNNING 重置回 QUEUED（重启后调用，计划书 7.3） */
    @Update("UPDATE t_submission SET status = 'REQUEUE' WHERE status = 'RUNNING'")
    int resetRunning();

    /** 恢复轮/调度轮用：REQUEUE -> QUEUED，让 rejudge 与看门狗的重置任务重新可领取 */
    @Update("UPDATE t_submission SET status = 'QUEUED' WHERE status = 'REQUEUE'")
    int requeueToQueued();

    /** 看门狗候选：RUNNING 且"状态转移时刻"（=开始评测）早于 N 分钟前——执行者疑似丢失 */
    @Select("SELECT id FROM t_submission WHERE status = 'RUNNING' " +
            "AND judged_time IS NOT NULL " +
            "AND judged_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE) LIMIT 100")
    List<Long> selectRunningTimedOut(@Param("minutes") int minutes);

    /** 看门狗重置：条件 CAS，任务刚好在瞬间自然完成（RUNNING 已没了）就不碰它 */
    @Update("UPDATE t_submission SET status = 'REQUEUE' " +
            "WHERE id = #{id} AND status = 'RUNNING'")
    int requeueRunning(@Param("id") Long id);

    /** 队列深度：/judge/status 监控接口与项目四 Agent 消费 */
    @Select("SELECT COUNT(*) FROM t_submission WHERE status = 'QUEUED'")
    long countQueued();

    @Select("SELECT COUNT(*) FROM t_submission WHERE status = 'RUNNING'")
    long countRunning();
}
