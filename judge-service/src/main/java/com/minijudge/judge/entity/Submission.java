package com.minijudge.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提交记录（t_submission）——同时充当"队列表"（计划书 7.2 的 DB 队列方案）：
 * 状态即队列位置，QUEUED=待评测、RUNNING=评测中、DONE=终态。
 *
 * <p>取舍（面试点）：DB 队列天然持久化、单实例简单可靠；
 * 换 MQ 的触发条件是"多评测机并行消费"，演进路径见计划书。</p>
 */
@Data
@TableName("t_submission")
public class Submission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long problemId;

    /** JAVA17 / CPP17，对应 t_language_config */
    private String language;

    /** 源码原文（上限 64KB，Controller 校验） */
    private String code;

    /** 状态机：QUEUED -> RUNNING -> DONE（异常路径 REQUEUE，见恢复逻辑） */
    private String status;

    /** 终局判定：AC/WA/TLE/MLE/RE/CE，DONE 前为 null */
    private String resultCode;

    private Integer passedCases;

    private Integer totalCases;

    /** 最大用例耗时/内存，判分聚合值 */
    private Long timeUsedMs;

    private Long memoryUsedKb;

    /** 编译/运行 stderr 摘要，给用户看 */
    private String errorMsg;

    private LocalDateTime submitTime;

    /**
     * 最后一次状态转移的时刻：领取置 RUNNING 时=评测开始时间（claim SQL 顺手写入），
     * 判完置 DONE 时=判定完成时间——看门狗用前者度量"RUNNING 持续时长"，
     * 用户侧展示用后者（DONE 后语义与原字段名一致）。
     */
    private LocalDateTime judgedTime;
}
