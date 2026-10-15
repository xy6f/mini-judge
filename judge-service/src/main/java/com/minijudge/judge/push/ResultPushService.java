package com.minijudge.judge.push;

import com.minijudge.judge.entity.Submission;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 判分结果实时推送（计划书第 6 章 WS 端点）。
 *
 * <p>前端按 /topic/submission/{id} 订阅单条提交；服务端判完即发。
 * 兜底：推送失败/用户断线时轮询 GET /api/submissions/{id} 仍能拿到结果——
 * "推是优化，拉是兜底"（面试讲 WebSocket 可靠性就这么答）。</p>
 */
@Service
@RequiredArgsConstructor
public class ResultPushService {

    private final SimpMessagingTemplate messaging;

    public void pushResult(Submission s) {
        messaging.convertAndSend("/topic/submission/" + s.getId(), Map.of(
                "submissionId", s.getId(),
                "status", s.getStatus(),
                "resultCode", s.getResultCode() == null ? "" : s.getResultCode(),
                "passed", s.getPassedCases() == null ? 0 : s.getPassedCases(),
                "total", s.getTotalCases() == null ? 0 : s.getTotalCases(),
                "timeUsedMs", s.getTimeUsedMs() == null ? 0 : s.getTimeUsedMs()));
    }
}
