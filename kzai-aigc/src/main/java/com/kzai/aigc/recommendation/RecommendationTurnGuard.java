package com.kzai.aigc.recommendation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.*;

/** 工具参数必须有本轮用户原文依据；省略的条件交给偏好服务保留，不能由模型默认。 */
public final class RecommendationTurnGuard {
    private static final String NUMBER="([0-9零〇一二两三四五六七八九十百千万]+(?:[.点][0-9零一二三四五六七八九]+)?)";
    private static final String SKILL="(?:Java(?:Script)?|Python|C\\+\\+|C#|Spring|SQL|Go|Rust|PHP|Kotlin|编程|开发|代码|前端|后端|设计|数据分析)";
    private static final String EXPERIENCE="(?:有[^，。；\\n]{0,12}(?:基础|经验)|没有[^，。；\\n]{0,10}(?:基础|经验)|没(?:写|学|接触)过(?:代码|编程|程序)|我(?:已经)?(?:会|熟悉|掌握|学过)\\s*"+SKILL+")";
    private RecommendationTurnGuard() { }
    public static RecommendationRequest apply(RecommendationRequest model,String userText) {
        if(userText==null)return model; // 非聊天入口使用已验证的结构化表单，不进行自然语言推断。
        String original=userText.trim();
        // “不要默认零基础/预算300元”是在纠正助手，不是声明自己的选课条件。
        String text=original.replaceAll("(?:不要|不能|别|不许|不应)[^，。；\\n]*(?:默认|假设|擅自)[^，。；\\n]*", "");
        var r=new RecommendationRequest();
        boolean adjustment=text.matches("(?si).*(预算|每周|每星期|每天|每小时|[0-9一二两三四五六七八九十]+\\s*(?:小时|周|星期)|周内|改成|改为|取消|不限|不限定|不限制|包含已|包括已|零基础|"+EXPERIENCE+").*")
                && !text.matches("(?si).*(想学|要学|学一下|学习(?!时间|周期|基础|记录|进度)|推荐.*课程|找.*课程).*");
        boolean experienceOnly=text.matches("(?si)\\s*(?:我)?"+EXPERIENCE+"[^，。；\\n]*[。！!]?\\s*");
        if(model.getKeyword()!=null && !model.getKeyword().isBlank()
                && !experienceOnly
                && !model.getKeyword().matches("(?si).*(预算|基础|小时|周|分类|已购|已学|^[0-9]+$).*")
                && text.toLowerCase(Locale.ROOT).contains(model.getKeyword().toLowerCase(Locale.ROOT)))adjustment=false;
        if(!adjustment)r.setGoal(original.substring(0,Math.min(500,original.length())));
        if(!adjustment && model.getKeyword()!=null && !model.getKeyword().isBlank()
                && text.toLowerCase(Locale.ROOT).contains(model.getKeyword().trim().toLowerCase(Locale.ROOT)))r.setKeyword(model.getKeyword().trim());
        if(text.matches("(?s).*(不(?:限制|限定)基础|基础不限|取消基础条件).*"))r.setFoundation("ANY");
        else if(text.contains("不是零基础"))r.setFoundation("INTERMEDIATE");
        else if(text.matches("(?s).*(零基础|没有[^，。；\\n]{0,10}(?:基础|经验)|没(?:写|学|接触)过(?:代码|编程|程序)).*"))r.setFoundation("BEGINNER");
        else if(text.matches("(?si).*"+EXPERIENCE+".*"))r.setFoundation("INTERMEDIATE");

        r.setClearBudget(text.matches("(?s).*(取消预算|预算不限|不(?:限制|限定|设)预算|去掉预算).*"));
        if(!r.isClearBudget()) {
            if(Pattern.compile("预算[^0-9]{0,5}-[0-9]").matcher(text).find())throw new IllegalArgumentException("预算不能为负数");
            Matcher budget=Pattern.compile("预算[^0-9零一二两三四五六七八九十百千万]{0,8}"+NUMBER).matcher(text);
            Matcher money=Pattern.compile(NUMBER+"\\s*(?:元|块)(?:以内|以下)").matcher(text);
            try {
                if(budget.find())r.setBudgetCents(number(budget.group(1)).multiply(BigDecimal.valueOf(text.substring(budget.end()).stripLeading().startsWith("分")?1:100)).intValueExact());
                else if(money.find())r.setBudgetCents(number(money.group(1)).multiply(BigDecimal.valueOf(100)).intValueExact());
            }catch(ArithmeticException ex){throw new IllegalArgumentException("预算金额超出支持范围");}
        }
        r.setClearTime(text.matches("(?s).*(取消时间|时间不限|周期不限|不(?:限制|限定)学习(?:时间|周期)|去掉时间).*"));
        Matcher weekly=Pattern.compile("(?:每周|每星期|一周)[^0-9零一二两三四五六七八九十百千万]{0,8}"+NUMBER+"\\s*(?:个)?小时").matcher(text);
        int weeklyStart=-1,weeklyEnd=-1;
        if(weekly.find()){r.setHoursPerWeek(number(weekly.group(1)));weeklyStart=weekly.start();weeklyEnd=weekly.end();}
        else {
            Matcher daily=Pattern.compile("每天[^0-9零一二两三四五六七八九十百千万]{0,8}"+NUMBER+"\\s*(?:个)?小时").matcher(text);
            if(daily.find())r.setHoursPerWeek(number(daily.group(1)).multiply(BigDecimal.valueOf(7)));
        }
        Matcher weeks=Pattern.compile(NUMBER+"\\s*(?:个)?(?:周|星期)").matcher(text);
        while(weeks.find())if(weeks.start()<weeklyStart || weeks.start()>=weeklyEnd)r.setWeeks(number(weeks.group(1)).intValueExact());
        Matcher category=Pattern.compile("分类\\s*(?:ID|编号)?[：: ]*([0-9]{1,20})",Pattern.CASE_INSENSITIVE).matcher(text);
        if(category.find())r.setCategoryId(category.group(1));
        r.setClearCategory(text.matches("(?s).*(取消分类|分类不限|不(?:限制|限定)课程范围|去掉分类).*"));
        if(text.matches("(?s).*(包含|包括|显示|查看).*(已购|已学).*"))r.setIncludeOwned(true);
        if(text.matches("(?s).*(不包含|不包括|排除).*(已购|已学).*"))r.setIncludeOwned(false);
        Matcher limit=Pattern.compile("(?:推荐|找)\\s*([1-9]|10)\\s*门").matcher(text);
        if(limit.find())r.setLimit(Integer.valueOf(limit.group(1)));
        return r;
    }
    static BigDecimal number(String value) {
        if(value.matches("[0-9]+(?:\\.[0-9]+)?"))return new BigDecimal(value);
        String[] decimal=value.replace('〇','零').replace('两','二').split("[.点]",-1);
        long total=0,section=0,digit=0;
        for(char ch:decimal[0].toCharArray()) {
            int n=Character.isDigit(ch)?ch-'0':"零一二三四五六七八九".indexOf(ch);
            if(n>=0){digit=n;continue;}
            int unit=switch(ch){case '十'->10;case '百'->100;case '千'->1000;case '万'->10000;default->throw new IllegalArgumentException("金额格式不支持");};
            if(unit==10000){total+=(section+digit)*unit;section=0;digit=0;}
            else {section+=(digit==0?1:digit)*unit;digit=0;}
        }
        BigDecimal result=BigDecimal.valueOf(total+section+digit);
        if(decimal.length>1){StringBuilder fraction=new StringBuilder();for(char ch:decimal[1].toCharArray()){
            if(Character.isDigit(ch))fraction.append(ch);else {int n="零一二三四五六七八九".indexOf(ch);if(n<0)throw new IllegalArgumentException("小数格式不支持");fraction.append(n);}
        }result=result.add(new BigDecimal("0."+fraction));}
        return result;
    }
}
