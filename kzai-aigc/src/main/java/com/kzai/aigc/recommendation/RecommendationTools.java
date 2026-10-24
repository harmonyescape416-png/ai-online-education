package com.kzai.aigc.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.tools.*;
import com.kzai.common.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.BiFunction;

@Component
@RequiredArgsConstructor
public class RecommendationTools {
    public static final String NAME="recommendCourses";
    private final RecommendationService service;
    private final ObjectMapper json;
    private final ToolAudit audit;
    public List<FunctionCallback> callbacks() {
        return List.of(FunctionCallback.builder().function(NAME,(BiFunction<RecommendationRequest,ToolContext,String>)this::recommend)
                .description("用户已说明学习主题就先查询，无需先追问可选条件。goal=用户目标，keyword=用户原文中的简短主题，foundation=BEGINNER/INTERMEDIATE/ANY，budgetCents=单门预算分。基础、预算、时间没有明确给出时不得默认或补造，省略以保留本会话条件。clearBudget/clearTime/clearCategory撤销条件。只读，不下单。解释只能依据返回的message、reasonCodes、diagnostics、nextAction和课程事实；服务故障不等于无匹配。金额向用户展示元，不展示工具名、状态码或执行过程。")
                .inputType(RecommendationRequest.class).build());
    }
    public String recommend(RecommendationRequest request,ToolContext context) {
        long started=System.currentTimeMillis();
        String status="UNAUTHORIZED";
        int count=0;
        try {
            CourseTools.restoreUserContext(context);
            Long uid=CourseTools.userIdOf(context);
            String session=CourseTools.strOf(context,ToolContextKeys.SESSION_ID);
            if(uid==null || session==null) return json.writeValueAsString(Map.of("status","UNAUTHORIZED","message","缺少可信用户会话"));
            var round=CourseTools.holderOf(context);
            if(round!=null && !round.tryAcquireToolCall(NAME)) {
                status="TOOL_BUDGET_EXCEEDED";return json.writeValueAsString(Map.of("status",status));
            }
            var patch=request==null?new RecommendationRequest():request;
            if(round!=null)patch=RecommendationTurnGuard.apply(patch,round.recommendationUserText());
            var result=service.recommend(uid,session,patch);
            if(round!=null)round.recommendationResult(result);
            status=String.valueOf(result.get("status"));
            count=((Number)result.getOrDefault("count",0)).intValue();
            for(String group:List.of("courses","ownedCourses"))if(round!=null && result.get(group) instanceof List<?> cards) for(Object c:cards) {
                if(c instanceof Map<?,?> card) {
                    @SuppressWarnings("unchecked") Map<String,Object> payload=(Map<String,Object>)card;
                    round.addCard(CourseTools.CARD_COURSE,"course:"+card.get("courseId"),payload);
                }
            }
            return json.writeValueAsString(result);
        }catch(Exception e){
            boolean invalid=e instanceof com.kzai.common.exception.BizException || e instanceof IllegalArgumentException;
            status=invalid?"INVALID_CONDITIONS":"RECOMMENDATION_UNAVAILABLE";
            var failure=Map.<String,Object>of("status",status,"message",invalid?"推荐条件不合法，请检查预算、基础或时间设置。":"推荐查询暂时无法完成，请稍后重试。","courses",List.of(),"count",0,
                    "reasonCodes",List.of(status));
            var round=CourseTools.holderOf(context);if(round!=null)round.recommendationResult(failure);
            try{return json.writeValueAsString(failure);}catch(Exception ignored){return "{\"status\":\"RECOMMENDATION_UNAVAILABLE\"}";}
        }finally{
            audit.record(NAME,CourseTools.strOf(context,ToolContextKeys.REQUEST_ID),CourseTools.strOf(context,ToolContextKeys.SESSION_ID),
                    System.currentTimeMillis()-started,Set.of("OK","NOT_FOUND","NEEDS_CLARIFICATION").contains(status),count,status);
            UserContext.clear();
        }
    }
}
