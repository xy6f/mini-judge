package com.minijudge.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评测时间线日志（t_judge_log，计划书第 5 章）。
 *
 * <p>用途：一条提交的每个关键节点（DISPATCHED/COMPILED/CASE_n/RESULT/WATCHDOG/
 * INFRA_ERROR）各落一行——排查"第 3 个用例为什么 WA、当时耗时多少"不用翻服务日志；
 * 也是项目四 AIOps Agent 做故障定位的数据源（时间线留痕，面试好讲）。</p>
 */
@Data
@TableName("t_judge_log")
public class JudgeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long submissionId;

    /** 事件名：DISPATCHED / COMPILED / CASE_1..N / RESULT / REQUEUE / INFRA_ERROR */
    private String event;

    /** 人类可读细节：判定码+耗时+简短 stderr，超 512 截断（列宽 VARCHAR(512)） */
    private String detail;

    /** 显式赋值而非依赖 DB 默认值：同一 worker 内多条日志顺序可辨 */
    private LocalDateTime createdAt;
}
