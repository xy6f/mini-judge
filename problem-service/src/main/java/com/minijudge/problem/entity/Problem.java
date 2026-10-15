package com.minijudge.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题目实体（t_problem）。
 * 时限/内存限制是"每题可配"的——评测护栏参数随题下发，而不是全局写死（计划书 7.1）。
 */
@Data
@TableName("t_problem")
public class Problem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** EASY/MEDIUM/HARD，题单筛选用 */
    private String difficulty;

    /** 单用例 CPU 时限（毫秒），传给沙箱 timeout 包装 */
    private Integer timeLimitMs;

    /** 内存上限（KB），传给 docker --memory */
    private Integer memoryLimitKb;

    private String description;

    /** DRAFT/PUBLISHED：草稿题不出现在题单 */
    private String status;

    private LocalDateTime createdAt;
}
