package com.kzai.aigc.recommendation;
import com.kzai.aigc.session.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** 会话删除事务内同步清理偏好，不访问其他用户或会话。 */
@Component
@RequiredArgsConstructor
public class RecommendationProfileCleanup {
    private final JdbcTemplate db;
    @EventListener
    public void onDelete(ChatSessionService.Deleted event) {
        db.update("DELETE FROM ai_course_recommend_profile WHERE user_id=? AND session_id=?",event.userId(),event.sessionId());
    }
}
