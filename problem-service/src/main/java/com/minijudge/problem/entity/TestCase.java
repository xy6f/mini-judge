package com.minijudge.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 测试用例（t_test_case）。
 * 安全设计（计划书 10 章"用例泄露"）：输入输出存磁盘文件路径而非表内容，
 * 且 isSample=false 的题用例永不出现在面向用户的接口，只供 judge-service 内部拉取。
 */
@Data
@TableName("t_test_case")
public class TestCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long problemId;

    /** 输入文件绝对路径（容器内以只读挂载） */
    private String inputPath;

    /** 期望输出文件路径 */
    private String outputPath;

    /**
     * 样例用例：题面可见。
     * 注意必须显式 @TableField("is_sample")：MyBatis-Plus 生成的列名默认取字段名
     * 蛇形（sample -> sample），与建表脚本的 is_sample 不符，不标注会在 SELECT/INSERT
     * 时报 Unknown column 'sample'——这是"布尔字段带 is 前缀"的经典映射坑（W2 冒烟发现修复）。
     */
    @TableField("is_sample")
    private Boolean sample;

    private Integer orderNo;
}
