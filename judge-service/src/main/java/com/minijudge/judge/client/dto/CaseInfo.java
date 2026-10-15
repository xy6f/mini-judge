package com.minijudge.judge.client.dto;

import lombok.Data;

/** 用例元信息：judge 侧按路径读取输入输出文件（单机部署时共享磁盘卷，见 yml 注释） */
@Data
public class CaseInfo {
    private Long id;
    private String inputPath;
    private String outputPath;
    private Boolean sample;
    /** 用例序号：problem-service 已按此升序返回，judge 用它做 t_judge_log 的 CASE_n 事件号 */
    private Integer orderNo;
}
