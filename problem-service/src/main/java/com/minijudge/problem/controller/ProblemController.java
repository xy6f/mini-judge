package com.minijudge.problem.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.minijudge.common.Result;
import com.minijudge.problem.entity.Problem;
import com.minijudge.problem.entity.TestCase;
import com.minijudge.problem.mapper.ProblemMapper;
import com.minijudge.problem.mapper.TestCaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题库接口（计划书第 6 章）。只读接口；建题/传用例走管理端（ADMIN），W1 后补。
 *
 * <p>注意 cases 端点：非样例用例路径属于敏感数据，正式方案应由网关禁止外部
 * 访问该 cases 路由，并由 judge-service 经服务发现内网直连获取（lb://problem-service，
 * 不经网关）——这里以注释先行表达设计意图，W2 在网关加 Path  deny 规则落地。</p>
 */
@RestController
@RequestMapping("/api/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemMapper problemMapper;
    private final TestCaseMapper testCaseMapper;

    /** 题单：只列已发布题，可选难度过滤 */
    @GetMapping
    public Result<List<Problem>> list(@RequestParam(required = false) String difficulty) {
        var q = Wrappers.<Problem>lambdaQuery().eq(Problem::getStatus, "PUBLISHED");
        if (difficulty != null) {
            q.eq(Problem::getDifficulty, difficulty);
        }
        return Result.ok(problemMapper.selectList(q.orderByAsc(Problem::getId)));
    }

    /** 题面：含样例输入输出内容（样例可读，非样例只给数量） */
    @GetMapping("/{id}")
    public Result<Problem> detail(@PathVariable Long id) {
        Problem p = problemMapper.selectById(id);
        if (p == null) {
            return Result.fail(40400, "题目不存在");
        }
        return Result.ok(p);
    }

    /**
     * 评测用内部接口：judge-service 经 Feign 拉全部用例。
     * 返回实体含文件路径——所以仅限内网调用（见类注释）。
     */
    @GetMapping("/{id}/cases")
    public Result<List<TestCase>> cases(@PathVariable Long id) {
        return Result.ok(testCaseMapper.selectList(
                Wrappers.<TestCase>lambdaQuery()
                        .eq(TestCase::getProblemId, id)
                        .orderByAsc(TestCase::getOrderNo)));
    }
}
