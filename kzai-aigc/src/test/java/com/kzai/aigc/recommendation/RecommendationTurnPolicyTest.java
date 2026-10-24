package com.kzai.aigc.recommendation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationTurnPolicyTest {
    @Test void inventedDefaultsAndPreferenceClearsAreDiscarded() {
        var model=new RecommendationRequest();model.setGoal("零基础高性价比Python实战");model.setKeyword("数据分析");
        model.setFoundation("BEGINNER");model.setBudgetCents(30000);model.setHoursPerWeek(BigDecimal.valueOf(5));model.setWeeks(4);
        model.setClearBudget(true);model.setClearTime(true);model.setIncludeOwned(true);
        var r=RecommendationTurnGuard.apply(model,"我要学 Python");
        assertEquals("我要学 Python",r.getGoal());assertNull(r.getKeyword());assertNull(r.getFoundation());assertNull(r.getBudgetCents());
        assertNull(r.getHoursPerWeek());assertNull(r.getWeeks());assertNull(r.getIncludeOwned());assertFalse(r.isClearBudget());assertFalse(r.isClearTime());
    }
    @Test void explicitFoundationMoneyAndTimeAreKept() {
        var r=RecommendationTurnGuard.apply(new RecommendationRequest(),"我没有编程经验，想学Python，预算300元，每周5小时，4周学完");
        assertEquals("BEGINNER",r.getFoundation());assertEquals(30000,r.getBudgetCents());assertEquals(BigDecimal.valueOf(5),r.getHoursPerWeek());assertEquals(4,r.getWeeks());
    }
    @Test void chineseBudgetAndChangedConditionsKeepTheGoalOmitted() {
        var r=RecommendationTurnGuard.apply(new RecommendationRequest(),"预算改成三百元，每周两小时，四周学完");
        assertNull(r.getGoal());assertNull(r.getFoundation());assertEquals(30000,r.getBudgetCents());assertEquals(BigDecimal.valueOf(2),r.getHoursPerWeek());assertEquals(4,r.getWeeks());
    }
    @Test void explicitClearsAreNotInventedAndCanBeAppliedIndependently() {
        var r=RecommendationTurnGuard.apply(new RecommendationRequest(),"取消预算，不限制基础，时间不限");
        assertNull(r.getGoal());assertTrue(r.isClearBudget());assertTrue(r.isClearTime());assertEquals("ANY",r.getFoundation());
    }
    @Test void frequencyDoesNotTurnIntoAnInventedOneWeekDeadline() {
        var r=RecommendationTurnGuard.apply(new RecommendationRequest(),"一周五小时");
        assertEquals(BigDecimal.valueOf(5),r.getHoursPerWeek());assertNull(r.getWeeks());
        r=RecommendationTurnGuard.apply(new RecommendationRequest(),"每天一小时");
        assertNull(r.getGoal());assertEquals(BigDecimal.valueOf(7),r.getHoursPerWeek());
    }
    @Test void foundationOnlyFollowupDoesNotReplaceTheLearningGoal() {
        var model=new RecommendationRequest();model.setGoal("Python入门");
        var r=RecommendationTurnGuard.apply(model,"我没有编程经验");
        assertNull(r.getGoal());assertEquals("BEGINNER",r.getFoundation());
        model.setKeyword("Java");r=RecommendationTurnGuard.apply(model,"我已经会 Java");
        assertNull(r.getGoal());assertEquals("INTERMEDIATE",r.getFoundation());
        r=RecommendationTurnGuard.apply(model,"我已经会 java 和 Spring Boot");assertNull(r.getGoal());
    }
    @Test void WordingAboutStudyCommitmentIsNotAnExperienceClaim() {
        var r=RecommendationTurnGuard.apply(new RecommendationRequest(),"我会每天抽时间学Python");assertNull(r.getFoundation());
    }
    @Test void correctionAndCoursePriceAreNotPersonalPreferences() {
        var r=RecommendationTurnGuard.apply(new RecommendationRequest(),"不要默认我零基础或预算300元，我要学Python");
        assertNull(r.getFoundation());assertNull(r.getBudgetCents());
        r=RecommendationTurnGuard.apply(new RecommendationRequest(),"我想学Python，这门课标价300元");assertNull(r.getBudgetCents());
    }
    @Test void explicitNegativeBudgetIsRejectedAndMixedChineseAmountIsSupported() {
        assertThrows(IllegalArgumentException.class,()->RecommendationTurnGuard.apply(new RecommendationRequest(),"预算-1元"));
        assertEquals(30000,RecommendationTurnGuard.apply(new RecommendationRequest(),"预算3百元").getBudgetCents());
    }
    @Test void replyUsesOutcomeAndYuanInsteadOfInternalMetadata() {
        var result=Map.<String,Object>of("message","找到以下课程。","nextAction","","reasonCodes",List.of("SECRET_CODE"),
            "diagnostics",Map.of("namespace","secret-index"),"courses",List.of(Map.of("name","真实课程","priceYuan","300.00","price",30000,"free",0)));
        var text=RecommendationReplyPolicy.render(result);
        assertTrue(text.contains("¥300.00"));assertFalse(text.contains("30000"));assertFalse(text.contains("secret"));assertFalse(text.contains("SECRET_CODE"));
        text=RecommendationReplyPolicy.render(Map.of("message","推荐结果","courses",List.of(Map.of("name","Python_进阶[2026]","priceYuan","99.00","free",0))));
        assertTrue(text.contains("Python\\_进阶\\[2026\\]"),"展示转义不能删改真实课程名称");
    }
    @Test void ownedLearningCardsReachTheChatRoundAndAreClearedOnNextEmptyResult() {
        var service=org.mockito.Mockito.mock(RecommendationService.class);
        var tools=new RecommendationTools(service,new com.fasterxml.jackson.databind.ObjectMapper(),new com.kzai.aigc.tools.ToolAudit());
        var round=new com.kzai.aigc.tools.ToolResultHolder(3,"s","request");
        var context=new org.springframework.ai.chat.model.ToolContext(Map.of(
                com.kzai.aigc.tools.ToolContextKeys.USER_ID,3L,
                com.kzai.aigc.tools.ToolContextKeys.SESSION_ID,"s",
                com.kzai.aigc.tools.ToolContextKeys.ROUND,round));
        var result=Map.<String,Object>of("status","NOT_FOUND","count",0,"courses",List.of(),
                "ownedCourses",List.of(Map.of("courseId","11","name","真实已购课程","owned",true)));
        org.mockito.Mockito.when(service.recommend(org.mockito.ArgumentMatchers.eq(3L),org.mockito.ArgumentMatchers.eq("s"),org.mockito.ArgumentMatchers.any()))
                .thenReturn(result,Map.of("status","NOT_FOUND","courses",List.of(),"count",0));
        tools.recommend(new RecommendationRequest(),context);
        assertEquals(1,round.cards().size());assertEquals("course:11",round.cards().get(0).get("cardId"));
        assertEquals(result,round.recommendationResult());
        tools.recommend(new RecommendationRequest(),context);assertTrue(round.cards().isEmpty());
    }
}
