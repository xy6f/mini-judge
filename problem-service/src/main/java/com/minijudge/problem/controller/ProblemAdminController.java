package com.minijudge.problem.controller;

import com.minijudge.common.Result;
import com.minijudge.problem.entity.Problem;
import com.minijudge.problem.entity.TestCase;
import com.minijudge.problem.mapper.ProblemMapper;
import com.minijudge.problem.mapper.TestCaseMapper;
import com.minijudge.problem.mapper.UserRoleMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 题库管理接口（计划书第 6 章：POST /problems、PUT cases，ADMIN 用）。
 *
 * <p>鉴权说明（RBAC 收口，本类现状）：建题/传用例已改为服务端强校验 ADMIN——
 * 身份取网关注入并覆盖伪造的 X-User-Id 头，角色现查 t_user（见 UserRoleMapper
 * 的跨服务读表妥协说明）。历史版本"校验交给网关基础设施"的分工在实际部署里
 * 从未落地：网关只做身份注入、不做细粒度 RBAC，等于管理接口裸奔，安全扫描
 * 因测试脚本没覆盖"合法普通用户越权管理操作"而漏报。现收口为双重防线：
 * 网关保证"知道你是谁"，本服务保证"只有 ADMIN 能写题库"。
 * 缺身份头/查无角色/角色非 ADMIN 一律拒绝（fail-closed，无角色数据视为 USER）。</p>
 *
 * <p>用例数据的落盘取舍（重要设计说明）：POST /{id}/cases 用 JSON body 传
 * base64 编码的用例内容，落盘到 {cases-root}/{problemId}/{orderNo}.in/.out。
 * 真实生产系统绝不该让大用例穿 JSON——应走对象存储（OSS/S3）：管理端直传
 * 预签名 URL，本服务只登记存储 key 并校验；或 multipart 上传+病毒扫描。
 * 这里选"base64 落本地盘"是因为 MVP 单机演示链路最短、judge-service 同机
 * 直接按路径读（compose 部署时两服务共享 /data/cases 卷），把"大文件通道
 * 与元数据通道分离"记为架构演进项。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/problems")
@RequiredArgsConstructor
public class ProblemAdminController {

    private final ProblemMapper problemMapper;
    private final TestCaseMapper testCaseMapper;
    private final UserRoleMapper userRoleMapper;

    /**
     * 管理写操作鉴权：身份来自网关注入的 X-User-Id（网关已剥离伪造值并以 JWT 结果
     * 覆盖，见 JwtGlobalFilter），角色现查 t_user。
     * 返回 null=放行；返回非空 Result=拒绝（调用方直接返回它）。
     * fail-closed：无身份头 / 查无角色数据 / 角色非 ADMIN 全部按普通用户拒绝（40300）。
     */
    private Result<Map<String, Object>> requireAdmin(Long userId) {
        if (userId == null) {
            // 绕过网关裸连 8103 的调用（理论上生产不暴露端口）也没有身份头可查——同样拒绝
            return Result.fail(40300, "缺少身份头 X-User-Id，管理接口拒绝");
        }
        String role;
        try {
            role = userRoleMapper.selectRoleById(userId);
        } catch (Exception e) {
            log.warn("role query failed for user {}, reject by fail-closed: {}", userId, e.getMessage());
            return Result.fail(40300, "角色查询失败，管理接口拒绝");
        }
        // 无角色数据（null/空）视为 USER（向后兼容 + 最小权限）
        if (role == null || !"ADMIN".equalsIgnoreCase(role.trim())) {
            return Result.fail(40300, "需要 ADMIN 角色，普通用户无权出题");
        }
        return null;
    }

    /** 用例文件根目录：相对路径按进程工作目录解析；docker 部署固定为共享卷 /data/cases */
    @Value("${mini-judge.problem.cases-root:cases}")
    private String casesRoot;

    /** 建题请求体：只给必要字段，时限/难度可缺省走安全默认 */
    @Data
    public static class CreateProblemReq {
        private String title;
        /** EASY/MEDIUM/HARD，缺省 EASY */
        private String difficulty;
        /** 单用例 CPU 时限毫秒，缺省 2000（计划书默认值） */
        private Integer timeLimitMs;
        /** 内存上限 KB，缺省 262144（256MB） */
        private Integer memoryLimitKb;
        private String description;
        /** DRAFT/PUBLISHED：缺省 PUBLISHED 便于演示；正式流程应默认 DRAFT 走审核 */
        private String status;
    }

    /** 传用例请求体：每个用例一段 base64 输入 + base64 期望输出 */
    @Data
    public static class CaseReq {
        /** 输入文件的 base64（UTF-8 文本先转字节再编码） */
        private String inputBase64;
        /** 期望输出文件的 base64 */
        private String outputBase64;
        /** 是否题面可见样例，缺省 false（最小可见原则：默认非样例） */
        private Boolean sample;
    }

    @Data
    public static class UploadCasesReq {
        /** 用例按数组顺序编号 1..n（order_no），与 UK(problem_id,order_no) 对应 */
        private List<CaseReq> cases;
    }

    /** 建题：入库 t_problem，返回新题 id（管理端后续用它传用例）。ADMIN 专属 */
    @PostMapping
    public Result<Map<String, Object>> create(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestBody CreateProblemReq req) {
        Result<Map<String, Object>> denied = requireAdmin(userId);
        if (denied != null) {
            return denied;
        }
        if (req.getTitle() == null || req.getTitle().isBlank()
                || req.getDescription() == null || req.getDescription().isBlank()) {
            return Result.fail(40001, "title/description 不能为空");
        }
        String difficulty = req.getDifficulty() == null ? "EASY" : req.getDifficulty();
        if (!List.of("EASY", "MEDIUM", "HARD").contains(difficulty)) {
            return Result.fail(40002, "difficulty 仅支持 EASY/MEDIUM/HARD");
        }
        if (req.getTimeLimitMs() != null && (req.getTimeLimitMs() <= 0 || req.getTimeLimitMs() > 60_000)) {
            // 上限 60s：时限也是防刷项，不给一题配"跑一小时"的护栏空洞
            return Result.fail(40003, "timeLimitMs 需在 1..60000");
        }
        Problem p = new Problem();
        p.setTitle(req.getTitle());
        p.setDifficulty(difficulty);
        p.setTimeLimitMs(req.getTimeLimitMs() == null ? 2000 : req.getTimeLimitMs());
        p.setMemoryLimitKb(req.getMemoryLimitKb() == null ? 262144 : req.getMemoryLimitKb());
        p.setDescription(req.getDescription());
        p.setStatus("DRAFT".equals(req.getStatus()) ? "DRAFT" : "PUBLISHED");
        problemMapper.insert(p);
        log.info("admin created problem {} ({})", p.getId(), p.getTitle());
        return Result.ok(Map.of("id", p.getId()));
    }

    /**
     * 传用例：base64 解码落盘 {cases-root}/{problemId}/{n}.in/.out，元信息入库。
     * 全有或全无策略：中途失败直接 4xx 返回，已落盘的孤儿文件由下次同 orderNo
     * 覆盖写自然收敛（幂等）；生产用"先传临时区、全部成功后原子改名+登记"两阶段提交。
     */
    @PostMapping("/{id}/cases")
    public Result<Map<String, Object>> uploadCases(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody UploadCasesReq req) {
        Result<Map<String, Object>> denied = requireAdmin(userId);
        if (denied != null) {
            return denied;
        }
        if (problemMapper.selectById(id) == null) {
            return Result.fail(40400, "题目不存在");
        }
        List<CaseReq> cases = req.getCases();
        if (cases == null || cases.isEmpty()) {
            return Result.fail(40001, "cases 不能为空");
        }
        if (cases.size() > 512) {
            return Result.fail(40002, "单题用例数上限 512（防元数据刷爆）");
        }
        // 解析成绝对路径入库：judge-service 可能以不同工作目录启动，相对路径会指错地方
        Path root = Paths.get(casesRoot).toAbsolutePath().normalize().resolve(String.valueOf(id));
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            return Result.fail(50001, "用例目录创建失败: " + e.getMessage());
        }
        int order = 0;
        try {
            for (CaseReq c : cases) {
                order++;
                byte[] in = decode(c.getInputBase64(), "inputBase64", order);
                byte[] out = decode(c.getOutputBase64(), "outputBase64", order);
                Path inFile = root.resolve(order + ".in");
                Path outFile = root.resolve(order + ".out");
                Files.write(inFile, in);
                Files.write(outFile, out);
                TestCase tc = new TestCase();
                tc.setProblemId(id);
                tc.setInputPath(inFile.toString());
                tc.setOutputPath(outFile.toString());
                tc.setSample(c.getSample() != null && c.getSample());
                tc.setOrderNo(order);
                testCaseMapper.insert(tc);
            }
        } catch (IllegalArgumentException e) {
            return Result.fail(40003, e.getMessage());
        } catch (IOException e) {
            return Result.fail(50002, "用例落盘失败: " + e.getMessage());
        }
        log.info("admin uploaded {} cases for problem {}", cases.size(), id);
        return Result.ok(Map.of("problemId", id, "uploaded", cases.size(),
                "dir", root.toString()));
    }

    /** base64 解码：空/非法都抛出带用例号的清晰错误（给管理端定位用） */
    private byte[] decode(String b64, String field, int order) {
        if (b64 == null) {
            throw new IllegalArgumentException("第 " + order + " 个用例缺少 " + field);
        }
        try {
            return Base64.getDecoder().decode(b64.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("第 " + order + " 个用例的 " + field + " 不是合法 base64");
        }
    }
}
