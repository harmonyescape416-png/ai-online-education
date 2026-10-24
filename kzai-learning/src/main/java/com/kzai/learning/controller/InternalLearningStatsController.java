package com.kzai.learning.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.result.R;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.QuestionReplyTask;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.mapper.QuestionReplyTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 learning 内部统计（内部签名；供 user 运营看板聚合）。
 * 「待处理问答」口径（不使用 question.status=0——其原始语义是 0未查看/1已查看，与待处理无关）：
 * <b>无任何回复（latest_answer_id 为空）且无进行中 AI 任务（无 active 任务）</b>的问题数。
 */
@RestController
@RequestMapping("/internal/learning/stats")
@RequiredArgsConstructor
public class InternalLearningStatsController {

    private final InteractionQuestionMapper questionMapper;
    private final InteractionReplyMapper replyMapper;
    private final QuestionReplyTaskMapper taskMapper;

    @GetMapping("/questions")
    public R<Map<String, Object>> questions(@RequestParam(defaultValue = "1") int days) {
        int n = Math.max(1, Math.min(days, 30));
        LocalDateTime start = LocalDate.now().minusDays(n - 1L).atStartOfDay();
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("todayQuestions", questionMapper.selectCount(Wrappers.<InteractionQuestion>lambdaQuery()
                .ge(InteractionQuestion::getCreateTime, LocalDate.now().atStartOfDay())));
        out.put("totalQuestions", questionMapper.selectCount(null));

        // 待处理：无回复 且 无进行中任务（真实状态推导，不用 status=0 冒充）
        long pending = 0;
        List<InteractionQuestion> all = questionMapper.selectList(Wrappers.<InteractionQuestion>lambdaQuery()
                .select(InteractionQuestion::getId, InteractionQuestion::getLatestAnswerId));
        for (InteractionQuestion q : all) {
            if (q.getLatestAnswerId() != null) {
                continue; // 已有回复
            }
            Long active = taskMapper.selectCount(Wrappers.<QuestionReplyTask>lambdaQuery()
                    .eq(QuestionReplyTask::getQuestionId, q.getId())
                    .in(QuestionReplyTask::getStatus, QuestionReplyTask.STATUS_PENDING,
                            QuestionReplyTask.STATUS_RUNNING));
            if (active == null || active == 0) {
                pending++;
            }
        }
        out.put("pendingQuestions", pending);

        // 提问每日趋势
        List<Map<String, Object>> trend = new ArrayList<>();
        List<InteractionQuestion> recent = questionMapper.selectList(Wrappers.<InteractionQuestion>lambdaQuery()
                .ge(InteractionQuestion::getCreateTime, start)
                .select(InteractionQuestion::getCreateTime));
        for (int i = 0; i < n; i++) {
            LocalDate d = LocalDate.now().minusDays(n - 1L - i);
            long cnt = recent.stream()
                    .filter(q -> q.getCreateTime() != null && d.equals(q.getCreateTime().toLocalDate()))
                    .count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("count", cnt);
            trend.add(m);
        }
        out.put("questionTrend", trend);
        return R.ok(out);
    }
}
