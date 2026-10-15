package com.minijudge.judge.client.dto;

import lombok.Data;

/**
 * problem-service 返回的题面裁剪视图：judge 只关心护栏参数，
 * 显式建 DTO 而不是共享实体——服务间契约用最小视图，字段增删不互相牵连。
 */
@Data
public class ProblemInfo {
    private Long id;
    private String title;
    /** 单用例 CPU 时限（毫秒） */
    private Integer timeLimitMs;
    /** 内存上限（KB） */
    private Integer memoryLimitKb;
}
