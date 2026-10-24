package com.kzai.aigc.recommendation;

import java.util.*;

/** 推荐文本只由本轮真实工具结果生成，模型的调用过程、默认条件和原因猜测不直接流给用户。 */
public final class RecommendationReplyPolicy {
    private RecommendationReplyPolicy() { }
    public static final String SYSTEM_RULES="""
            推荐业务约束（适用于所有会话版本）：
            本段约束优先于历史模板中的旧工具名称和旧推荐流程，推荐检索只调用 recommendCourses。
            “我要学 Python”“想学 Java”已明确主题，必须先调用 recommendCourses，不先追问基础、预算和时间。
            只有主题或目标完全不明时才问一个关键问题。未明确给出的基础、预算、时间和偏好保持未限定，不能默认零基础、实战导向或高性价比。
            本轮只调整条件时沿用本会话已有条件；用户撤销条件时明确清除。推荐不代表授权购买。
            只根据工具实际返回的 status、reasonCodes、diagnostics、message、nextAction 和课程卡片回答。
            无候选、条件排除、已购排除、不可购买和检索/服务失败是不同原因，不能猜测索引未更新、课程缺关键词等。
            金额展示元，不向用户展示分、工具名、索引名、状态码或“现在调用工具”等执行过程。
            语义故障必须如实说明关键词降级，服务故障不得说成没有课程。只引用本轮课程事实与公开证据，不生成效果或就业承诺。
            """;
    public static String render(Map<String,Object> result) {
        if(result==null)return "暂时无法完成课程查询，请稍后重试。";
        StringBuilder text=new StringBuilder(String.valueOf(result.getOrDefault("message","暂时无法完成课程查询。")));
        for(String group:List.of("courses","ownedCourses"))if(result.get(group) instanceof List<?> cards){
            if(group.equals("ownedCourses")&&!cards.isEmpty())text.append("\n\n你已购或已加入学习的相关课程：");
            for(Object raw:cards)if(raw instanceof Map<?,?> c){
                text.append("\n\n").append(plain(c.get("name"))).append("，");
                if(group.equals("ownedCourses"))text.append(Boolean.TRUE.equals(c.get("completed"))?"已完成，当前标价":"已购或已加入学习，当前标价");
                if("1".equals(String.valueOf(c.get("free"))))text.append("免费");
                else text.append("¥").append(plain(c.get("priceYuan")));
                if(c.get("recommendationEvidence") instanceof List<?> evidence)for(Object e:evidence){
                    String fact=String.valueOf(e);
                    if(fact.startsWith("课程简介：")){text.append("。\n").append(plain(fact));break;}
                }
            }
        }
        String action=String.valueOf(result.getOrDefault("nextAction",""));
        if(!action.isBlank())text.append("\n\n").append(action);
        return text.toString();
    }
    private static String plain(Object value){
        // 转义而不是删字符，保留真实课程名中的下划线、方括号等内容。
        return String.valueOf(value==null?"":value).replace("\\","\\\\")
                .replace("[","\\[").replace("]","\\]").replace("`","\\`")
                .replace("*","\\*").replace("_","\\_").replace("<","&lt;").replace(">","&gt;");
    }
}
