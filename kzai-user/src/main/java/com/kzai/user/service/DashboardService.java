package com.kzai.user.service;

import com.kzai.common.result.R;
import com.kzai.user.client.AigcStatsClient;
import com.kzai.user.client.CourseStatsClient;
import com.kzai.user.client.LearningStatsClient;
import com.kzai.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M13 管理员运营数据看板（真实数据聚合，指标级降级）：
 * <ul>
 *   <li>用户数：user 服务本库真实统计；课程数/提问数/会话数：各服务内部统计接口；</li>
 *   <li>模型调用成功率/平均首字延迟：仅 provider=DASHSCOPE(REAL) 真实调用记录
 *       （ai_model_call_log）；<b>无真实数据时返回 null 并标注「暂无真实模型数据」，
 *       绝不以 M12 MOCK 评测数据冒充</b>；</li>
 *   <li>某下游服务不可用：仅该指标返回 unavailable（含原因），不得把全部数据显示为 0；</li>
 *   <li>支持今日/最近7天/最近30天窗口；统一 Asia/Shanghai 时区（服务端本地）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final UserMapper userMapper;
    private final CourseStatsClient courseStats;
    private final LearningStatsClient learningStats;
    private final AigcStatsClient aigcStats;

    public Map<String, Object> overview(int days) {
        int n = Math.max(1, Math.min(days, 30));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", n);
        out.put("generatedAt", java.time.LocalDateTime.now());

        // 用户数（本库）
        out.put("userCount", safe("user", () -> userMapper.selectCount(null)));

        // 课程数
        out.put("courseCount", safe("course", () -> {
            R<Long> r = courseStats.courseCount();
            return r != null && r.isSuccess() ? r.getData() : null;
        }));

        // 会话（aigc）
        Map<String, Object> aigc = callAigc(n);
        out.put("todaySessions", aigc.get("todaySessions"));
        out.put("totalSessions", aigc.get("totalSessions"));
        out.put("sessionTrend", aigc.get("sessionTrend"));
        out.put("aigcAvailable", aigc.get("available"));

        // 提问（learning）
        Map<String, Object> learning = callLearning(n);
        out.put("todayQuestions", learning.get("todayQuestions"));
        out.put("totalQuestions", learning.get("totalQuestions"));
        out.put("pendingQuestions", learning.get("pendingQuestions"));
        out.put("questionTrend", learning.get("questionTrend"));
        out.put("learningAvailable", learning.get("available"));

        // 模型运行指标（仅 REAL；无真实数据 → null + 提示）
        out.put("modelCallTotal", aigc.get("modelCallTotal"));
        out.put("modelCallSuccess", aigc.get("modelCallSuccess"));
        out.put("modelSuccessRate", aigc.get("modelSuccessRate"));
        out.put("avgFirstTokenMs", aigc.get("avgFirstTokenMs"));
        out.put("firstTokenSamples", aigc.get("firstTokenSamples"));
        boolean noRealData = aigc.get("modelCallTotal") == null
                || Integer.parseInt(String.valueOf(aigc.get("modelCallTotal"))) == 0;
        out.put("modelDataNote", noRealData ? "暂无真实模型数据（无 DASHSCOPE_API_KEY 或尚无 REAL 调用；MOCK 调用不计入）" : null);
        return out;
    }

    private Map<String, Object> callAigc(int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", false);
        try {
            R<Map<String, Object>> r = aigcStats.overview(days);
            if (r != null && r.isSuccess() && r.getData() != null) {
                m.putAll(r.getData());
                m.put("available", true);
            }
        } catch (RuntimeException ex) {
            log.warn("[dashboard] aigc 统计不可用: {}", ex.getMessage());
        }
        return m;
    }

    private Map<String, Object> callLearning(int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", false);
        try {
            R<Map<String, Object>> r = learningStats.questions(days);
            if (r != null && r.isSuccess() && r.getData() != null) {
                m.putAll(r.getData());
                m.put("available", true);
            }
        } catch (RuntimeException ex) {
            log.warn("[dashboard] learning 统计不可用: {}", ex.getMessage());
        }
        return m;
    }

    private Object safe(String source, java.util.function.Supplier<Object> call) {
        try {
            Object v = call.get();
            return v == null ? Map.of("unavailable", true, "source", source) : v;
        } catch (RuntimeException ex) {
            log.warn("[dashboard] {} 统计不可用: {}", source, ex.getMessage());
            return Map.of("unavailable", true, "source", source);
        }
    }
}
