package com.kzai.aigc.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.aigc.session.entity.ChatSession;
import com.kzai.aigc.session.mapper.ChatSessionMapper;
import com.kzai.aigc.stats.entity.AiModelCallLog;
import com.kzai.aigc.stats.mapper.AiModelCallLogMapper;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 aigc 内部统计（内部签名；供 user 运营看板聚合，网关不可达）。
 * 统计口径：
 * <ul>
 *   <li>模型指标仅统计 provider=DASHSCOPE（REAL）行——MOCK 调用绝不计入真实成功率/延迟；</li>
 *   <li>成功率分母 = source=CHAT 的业务请求轮次（每轮恰好一条日志，底层重试不单独成行）；</li>
 *   <li>首字延迟 = source=CHAT 成功轮的 first_token_ms（仅真实测量值，NULL 不计入均值）；</li>
 *   <li>样本不足时如实返回 samples=0 与 null 指标，不虚构。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/ai/stats")
@RequiredArgsConstructor
public class InternalAigcStatsController {

    private final ChatSessionMapper sessionMapper;
    private final AiModelCallLogMapper callLogMapper;

    /** 概览 + 趋势（days=1 今日 / 7 / 30） */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(defaultValue = "1") int days) {
        int n = Math.max(1, Math.min(days, 30));
        LocalDateTime start = LocalDate.now().minusDays(n - 1L).atStartOfDay();
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("todaySessions", sessionMapper.selectCount(Wrappers.<ChatSession>lambdaQuery()
                .ge(ChatSession::getCreateTime, LocalDate.now().atStartOfDay())));
        out.put("totalSessions", sessionMapper.selectCount(null));

        // 会话每日趋势
        List<Map<String, Object>> trend = new ArrayList<>();
        List<ChatSession> sessions = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                .ge(ChatSession::getCreateTime, start)
                .select(ChatSession::getCreateTime));
        for (int i = 0; i < n; i++) {
            LocalDate d = LocalDate.now().minusDays(n - 1L - i);
            String day = d.toString();
            long cnt = sessions.stream()
                    .filter(s -> s.getCreateTime() != null && d.equals(s.getCreateTime().toLocalDate()))
                    .count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day);
            m.put("count", cnt);
            trend.add(m);
        }
        out.put("sessionTrend", trend);

        // 模型运行指标（仅 REAL）
        out.putAll(modelMetrics(start));
        return R.ok(out);
    }

    private Map<String, Object> modelMetrics(LocalDateTime start) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<AiModelCallLog> chats = callLogMapper.selectList(Wrappers.<AiModelCallLog>lambdaQuery()
                .eq(AiModelCallLog::getSource, AiModelCallLog.SOURCE_CHAT)
                .eq(AiModelCallLog::getProvider, "DASHSCOPE")
                .ge(AiModelCallLog::getCreateTime, start));
        long total = chats.size();
        long ok = chats.stream().filter(c -> c.getSuccess() != null && c.getSuccess() == 1).count();
        m.put("modelCallTotal", total);
        m.put("modelCallSuccess", ok);
        m.put("modelSuccessRate", total == 0 ? null : Math.round(ok * 1000.0 / total) / 10.0);
        DoubleSummaryStatistics ft = chats.stream()
                .filter(c -> c.getSuccess() != null && c.getSuccess() == 1)
                .filter(c -> c.getFirstTokenMs() != null)
                .mapToDouble(AiModelCallLog::getFirstTokenMs)
                .summaryStatistics();
        m.put("avgFirstTokenMs", ft.getCount() == 0 ? null : Math.round(ft.getAverage()));
        m.put("firstTokenSamples", ft.getCount());
        return m;
    }

    /** 90 天保留：清理过期模型调用日志（分批；返回删除行数） */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 120_000)
    public void retain90Days() {
        LocalDateTime expire = LocalDateTime.now().minusDays(90);
        long deleted = 0;
        while (true) {
            List<AiModelCallLog> batch = callLogMapper.selectList(Wrappers.<AiModelCallLog>lambdaQuery()
                    .lt(AiModelCallLog::getCreateTime, expire)
                    .last("LIMIT 500"));
            if (batch.isEmpty()) {
                break;
            }
            deleted += callLogMapper.deleteBatchIds(batch.stream().map(AiModelCallLog::getId).toList());
            if (batch.size() < 500) {
                break;
            }
        }
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger("RETENTION")
                    .info("retention=MODEL_CALL table=ai_model_call_log deleted={} before={}", deleted, expire);
        }
    }
}
