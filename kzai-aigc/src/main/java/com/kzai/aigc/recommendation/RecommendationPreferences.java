package com.kzai.aigc.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.session.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 每用户、每会话独立；先检查会话归属，再读取/合并偏好。 */
@Component
@RequiredArgsConstructor
public class RecommendationPreferences {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ChatSessionService sessions;
    public RecommendationRequest get(long userId,String sessionId) {
        sessions.requireOwned(userId,sessionId);
        var rows=db.queryForList("SELECT preferences FROM ai_course_recommend_profile WHERE user_id=? AND session_id=?",userId,sessionId);
        try { return rows.isEmpty()?new RecommendationRequest():json.readValue(String.valueOf(rows.get(0).get("preferences")),RecommendationRequest.class); }
        catch(Exception e){throw new IllegalStateException("推荐偏好读取失败",e);}
    }
    public RecommendationRequest merge(long userId,String sessionId,RecommendationRequest patch) {
        sessions.requireOwned(userId,sessionId);
        return tx.execute(status -> {
            db.update("INSERT IGNORE INTO ai_course_recommend_profile(user_id,session_id,preferences,update_time) VALUES(?,?,'{}',NOW(3))",userId,sessionId);
            String raw=db.queryForObject("SELECT preferences FROM ai_course_recommend_profile WHERE user_id=? AND session_id=? FOR UPDATE",String.class,userId,sessionId);
            try {
                RecommendationRequest r=json.readValue(raw,RecommendationRequest.class);
                if(patch.getGoal()!=null) {
                    if(!patch.getGoal().trim().equals(r.getGoal()))r.setKeyword(null);
                    r.setGoal(patch.getGoal().trim());
                }
                if(patch.getKeyword()!=null)r.setKeyword(patch.getKeyword().trim());
                if(patch.getFoundation()!=null)r.setFoundation(patch.getFoundation());
                if(patch.getBudgetCents()!=null || patch.isClearBudget())r.setBudgetCents(patch.isClearBudget()?null:patch.getBudgetCents());
                if(patch.isClearTime()){r.setHoursPerWeek(null);r.setWeeks(null);}
                {
                    if(patch.getHoursPerWeek()!=null)r.setHoursPerWeek(patch.getHoursPerWeek());
                    if(patch.getWeeks()!=null)r.setWeeks(patch.getWeeks());
                }
                if(patch.getCategoryId()!=null || patch.isClearCategory())r.setCategoryId(patch.isClearCategory()?null:patch.getCategoryId());
                if(patch.getLimit()!=null)r.setLimit(patch.getLimit());
                if(patch.getIncludeOwned()!=null)r.setIncludeOwned(patch.getIncludeOwned());
                db.update("UPDATE ai_course_recommend_profile SET preferences=?,update_time=NOW(3) WHERE user_id=? AND session_id=?",json.writeValueAsString(r),userId,sessionId);
                return r;
            }catch(Exception e){throw new IllegalStateException("推荐偏好保存失败",e);}
        });
    }
}
