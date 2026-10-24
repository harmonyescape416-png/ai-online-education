package com.kzai.aigc.recommendation;

import com.kzai.aigc.client.*;
import com.kzai.aigc.tools.CourseTools;
import com.kzai.common.exception.BizException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** 只读推荐：召回不是事实来源，所有卡片均从课程服务实时复核后生成。 */
@Service
@RequiredArgsConstructor
public class RecommendationService {
    private final RecommendationPreferences preferences;
    private final RecommendationIndex index;
    private final CourseClient courses;
    private final RecommendationLearningClient learning;
    private final TradeClient trade;
    private final Validator validator;
    // ES cosine _score=(1+cosine)/2。初始 0.6 会把无关农业目标与软件课程匹配；
    // 此值经小样本校准，换模型/课程领域后需要重跑评测，不能当作通用概率。
    @Value("${kzai.aigc.recommendation-min-score:0.78}") private double minScore=0.78;

    public Map<String,Object> recommend(long userId,String sessionId,RecommendationRequest patch) {
        var errors=validator.validate(patch);
        if(!errors.isEmpty()) throw new BizException("推荐条件不合法："+errors.iterator().next().getPropertyPath());
        RecommendationRequest r=preferences.merge(userId,sessionId,patch);
        String goal=r.getGoal();
        if(goal==null || goal.isBlank() || goal.matches("推荐|推荐课程|随便|不知道|学点东西|帮我推荐")) {
            Trace pending=new Trace();pending.semantic="NOT_RUN";pending.keyword="NOT_RUN";pending.reasons.add("GOAL_REQUIRED");
            return pending.result("NEEDS_CLARIFICATION","NONE","你想学习什么主题，或希望完成什么事情？",r,List.of(),"");
        }
        int limit=r.getLimit()==null?5:r.getLimit();
        Trace trace=new Trace();
        LinkedHashMap<Long,Double> scores=new LinkedHashMap<>();
        Set<Long> semanticIds=new HashSet<>(), keywordIds=new HashSet<>();
        boolean semanticAvailable=false;
        String degradation=index.mock()?"当前按关键词查找课程。":null;
        trace.semantic=index.mock()?"DISABLED":"SUCCEEDED";
        if(!index.mock()) {
            try {
                int rank=0;
                String query=goal+("BEGINNER".equals(r.getFoundation())?" 适合零基础学习":"INTERMEDIATE".equals(r.getFoundation())?" 适合有基础的进阶学习":"");
                for(var hit:index.search(query,20,minScore)) {
                    Long id=CourseTools.parseCourseId(hit.courseId());
                    if(id!=null && semanticIds.add(id)) scores.merge(id,1.0/(60+(++rank)),Double::sum);
                }
                semanticAvailable=true;
            }catch(RuntimeException e){degradation="语义推荐暂不可用，已降级为关键词查询。";trace.semantic="UNAVAILABLE";trace.reasons.add("SEMANTIC_UNAVAILABLE");}
        }
        try {
            String keyword=r.getKeyword()==null || r.getKeyword().isBlank()?searchKeyword(goal):r.getKeyword();
            var found=courses.aiSearch(keyword,10);
            if(found==null || !found.isSuccess()) throw new IllegalStateException();
            int rank=0;
            if(found.getData()!=null) for(var item:found.getData()) {
                Long id=CourseTools.parseCourseId(item.getId());
                if(id!=null && keywordIds.add(id))scores.merge(id,1.0/(60+(++rank)),Double::sum);
            }
        }catch(RuntimeException e){
            trace.keyword="UNAVAILABLE";trace.reasons.add("KEYWORD_UNAVAILABLE");
            if(!semanticAvailable)return trace.result("COURSE_SERVICE_UNAVAILABLE","UNAVAILABLE","课程查询暂不可用，请稍后重试。",r,List.of(),"请稍后重试。");
            degradation="关键词检索暂不可用，当前只使用语义候选。";
        }
        String mode=!semanticAvailable&&!index.mock()?"KEYWORD_FALLBACK":
                !semanticIds.isEmpty()?(!keywordIds.isEmpty()?"HYBRID":"SEMANTIC"):
                !keywordIds.isEmpty()||index.mock()?"KEYWORD_ONLY":"SEMANTIC";
        trace.counts.put("semanticCandidates",semanticIds.size());trace.counts.put("keywordCandidates",keywordIds.size());
        trace.counts.put("mergedCandidates",scores.size());
        if(scores.isEmpty()) {
            if(trace.incomplete())return trace.result("SEARCH_INCOMPLETE",mode,
                    degradation+" 本次暂未找到可推荐课程。",r,List.of(),"请稍后重试。");
            trace.reasons.add("NO_RECALLED_CANDIDATES");
            return trace.result("NOT_FOUND",mode,"暂时没有找到与当前学习目标匹配的可推荐课程。",r,List.of(),"可以换一个学习方向或关键词再查找。");
        }
        Set<String> excluded=new HashSet<>();
        Set<String> owned=new HashSet<>(), completed=new HashSet<>();
        try {
            var context=learning.current();
            if(context==null || !context.isSuccess() || context.getData()==null)throw new IllegalStateException();
                for(String key:List.of("enrolled","completed")) {
                    Object ids=context.getData().get(key);
                    if(ids instanceof Collection<?> values) values.forEach(id->{
                        owned.add(String.valueOf(id));if(key.equals("completed"))completed.add(String.valueOf(id));
                    });
                }
                var paid=trade.paidCourseIds(userId,new ArrayList<>(scores.keySet()));
                if(paid==null || !paid.isSuccess() || paid.getData()==null)throw new IllegalStateException();
                paid.getData().forEach(id->owned.add(String.valueOf(id)));
                if(!Boolean.TRUE.equals(r.getIncludeOwned()))excluded.addAll(owned);
        }catch(RuntimeException e){trace.reasons.add("PROFILE_UNAVAILABLE");return trace.result("PROFILE_UNAVAILABLE",mode,"暂时无法核对你的已购和学习记录，本次推荐未完成。",r,List.of(),"请稍后重试。");}
        List<Map<String,Object>> cards=new ArrayList<>();
        List<Map<String,Object>> ownedCards=new ArrayList<>();
        List<Map.Entry<Long,Double>> ranked=new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<Long,Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for(var candidate:ranked) {
            trace.add("evaluatedCandidates");
            boolean rejected=false;
            boolean excludedOwned=excluded.contains(String.valueOf(candidate.getKey()));
            if(excludedOwned)trace.reject("OWNED_OR_LEARNED","ownedOrLearned");
            CourseAiItem c;
            try {
                var current=courses.aiDetail(candidate.getKey());
                if(current==null || !current.isSuccess())throw new IllegalStateException();
                c=current.getData();
            }catch(RuntimeException e){trace.reasons.add("LIVE_COURSE_CHECK_FAILED");return trace.result("COURSE_SERVICE_UNAVAILABLE",mode,"课程当前状态核对失败，本次推荐未完成。",r,List.of(),"请稍后重试。");}
            if(c==null || !String.valueOf(candidate.getKey()).equals(c.getId()) || !Integer.valueOf(2).equals(c.getStatus())
                    || !Boolean.TRUE.equals(c.getPurchasable())) {trace.reject("CURRENTLY_UNAVAILABLE","currentlyUnavailable");trace.add("filteredCandidates");continue;}
            Integer price=Integer.valueOf(1).equals(c.getFree())?0:c.getPrice();
            if(price==null || price<0) {trace.reject("PRICE_UNVERIFIED","priceUnverified");rejected=true;}
            else if(r.getBudgetCents()!=null && price>r.getBudgetCents()) {trace.reject("BUDGET_EXCEEDED","budgetExceeded");rejected=true;}
            if(r.getCategoryId()!=null && !r.getCategoryId().equals(c.getCategoryId())) {trace.reject("OUTSIDE_COURSE_SCOPE","outsideCourseScope");rejected=true;}
            String audience=Optional.ofNullable(c.getUsePeople()).orElse("");
            boolean beginner=audience.matches("(?si).*(零基础|无基础|初学|入门|beginner).*"),
                    advanced=audience.matches("(?si).*(进阶|高级|(?:有|具备|需要|要求|掌握|熟悉).*(?:基础|经验)|intermediate|advanced|prerequisite).*" );
            if("BEGINNER".equals(r.getFoundation()) && advanced && !beginner) {trace.reject("FOUNDATION_MISMATCH","foundationMismatch");rejected=true;}
            BigDecimal available=r.getHoursPerWeek()!=null && r.getWeeks()!=null?r.getHoursPerWeek().multiply(BigDecimal.valueOf(r.getWeeks())).multiply(BigDecimal.valueOf(3600)):null;
            if(available!=null && c.getTotalDurationSeconds()!=null && BigDecimal.valueOf(c.getTotalDurationSeconds()).compareTo(available)>0) {trace.reject("TIME_EXCEEDED","timeExceeded");rejected=true;}
            if(rejected){trace.add("filteredCandidates");continue;}
            List<String> evidence=new ArrayList<>(), warnings=new ArrayList<>();
            evidence.add("召回方式："+(semanticIds.contains(candidate.getKey())?"学习目标的语义检索":"关键词匹配"));
            if(c.getIntroduce()!=null && !c.getIntroduce().isBlank())evidence.add("课程简介："+excerpt(c.getIntroduce()));
            if(!audience.isBlank())evidence.add("公开适用人群："+excerpt(audience));
            if(!beginner && !advanced && r.getFoundation()!=null && !"ANY".equals(r.getFoundation()))warnings.add("课程未明确声明适用基础，请先查看详情或咨询。");
            if(r.getBudgetCents()!=null)evidence.add("当前价格在你设置的单门课程预算内。");
            if(available!=null) {
                if(c.getTotalDurationSeconds()==null)warnings.add("课程视频总时长不完整，无法确认时间适配。");
                else evidence.add("已登记视频总时长 "+c.getTotalDurationSeconds()+" 秒，不超过设置的可用时间；练习时间另计。");
            } else if(r.getHoursPerWeek()!=null || r.getWeeks()!=null)warnings.add("时间条件未完整设置，本次不限制课程总时长。");
            Map<String,Object> card=new LinkedHashMap<>(CourseTools.courseCardPayload(c));
            card.put("validDuration",c.getValidDuration()); card.put("usePeople",c.getUsePeople());
            card.put("owned",owned.contains(c.getId()));card.put("completed",completed.contains(c.getId()));
            card.put("recommendationEvidence",evidence);card.put("recommendationWarnings",warnings);
            card.put("retrievalMode",mode);card.put("dataSource","COURSE_SERVICE_LIVE");
            card.put("matchedBy",semanticIds.contains(candidate.getKey())?"SEMANTIC":"KEYWORD");
            // 已购课程仅作学习入口，仍需通过公开状态及其他条件核对，不混入新课推荐。
            if(excludedOwned){
                trace.add("filteredCandidates");
                if(ownedCards.size()<limit)ownedCards.add(card);
                continue;
            }
            cards.add(card);
            if(cards.size()>=limit)break;
        }
        if(cards.isEmpty()) {
            var result=trace.incomplete()?trace.result("SEARCH_INCOMPLETE",mode,
                    (degradation==null?"本次查询未完整完成。":degradation)+" 本次未能推荐新课程。",r,cards,"请稍后重试。"):
                    trace.result("NOT_FOUND",mode,trace.exclusionMessage(),r,cards,trace.nextAction());
            if(!ownedCards.isEmpty()){
                result.put("ownedCourses",ownedCards);
                result.put("nextAction",trace.incomplete()?"可先打开已购课程卡片查看详情和学习入口，或稍后重试查找新课程。":"可打开已购课程卡片查看详情和学习入口。");
            }
            return result;
        }
        return trace.result("OK",mode,degradation==null?"找到以下符合当前条件的课程，价格和购买资格已核对。":degradation,r,cards,"");
    }
    /** 只去掉对话前后缀，不增补用户没有说过的方向或课程关键词。 */
    static String searchKeyword(String goal) {
        String keyword=goal.trim().replaceFirst("^(?:请|帮我)?(?:我)?(?:想要|想|要)?(?:学习|学|找|推荐)\\s*", "")
                .replaceFirst("(?:的)?课程[。！!？?]*$", "").trim();
        return keyword.isBlank()?goal:keyword;
    }
    private static final class Trace {
        final Set<String> reasons=new LinkedHashSet<>();
        final Map<String,Integer> counts=new LinkedHashMap<>();
        String semantic="SUCCEEDED",keyword="SUCCEEDED";
        Trace(){for(String key:List.of("semanticCandidates","keywordCandidates","mergedCandidates","evaluatedCandidates","filteredCandidates","returnedCandidates","ownedOrLearned","currentlyUnavailable","priceUnverified","budgetExceeded","outsideCourseScope","foundationMismatch","timeExceeded"))counts.put(key,0);}
        void add(String key){counts.merge(key,1,Integer::sum);}
        void reject(String code,String counter){reasons.add(code);add(counter);}
        boolean incomplete(){return "UNAVAILABLE".equals(semantic)||"UNAVAILABLE".equals(keyword);}
        String exclusionMessage(){
            List<String> parts=new ArrayList<>();
            if(reasons.contains("OWNED_OR_LEARNED"))parts.add("有候选已在你的已购或学习列表中");
            if(reasons.contains("BUDGET_EXCEEDED"))parts.add("有候选超出你设置的单门预算");
            if(reasons.contains("OUTSIDE_COURSE_SCOPE"))parts.add("有候选不在你限定的课程范围内");
            if(reasons.contains("FOUNDATION_MISMATCH"))parts.add("有候选的公开前置要求与你设置的基础条件不符");
            if(reasons.contains("TIME_EXCEEDED"))parts.add("有候选已登记的视频时长超过你设置的可用时间");
            if(reasons.contains("CURRENTLY_UNAVAILABLE"))parts.add("有候选目前不可推荐或购买");
            if(reasons.contains("PRICE_UNVERIFIED"))parts.add("有候选的当前价格暂时无法核实");
            return parts.isEmpty()?"本次没有找到可推荐课程。":"本次未能返回推荐课程："+String.join("；",parts)+"。";
        }
        String nextAction(){
            if(reasons.contains("BUDGET_EXCEEDED"))return "可以调整预算后重新查找。";
            if(reasons.contains("OWNED_OR_LEARNED"))return "你可以查看已学课程，或选择包含已购、已学课程后重新查找。";
            if(reasons.contains("OUTSIDE_COURSE_SCOPE"))return "可以调整课程范围后重新查找。";
            if(reasons.contains("TIME_EXCEEDED"))return "可以调整学习周期后重新查找。";
            if(reasons.contains("FOUNDATION_MISMATCH"))return "可以补充希望完成的具体任务，我再帮你寻找符合基础要求的课程。";
            return "可以换一个学习方向，或稍后再试。";
        }
        Map<String,Object> result(String status,String mode,String message,RecommendationRequest p,List<Map<String,Object>> cards,String action){
            Map<String,Object> out=RecommendationService.result(status,mode,message,p,cards);
            counts.put("returnedCandidates",cards.size());
            out.put("reasonCodes",List.copyOf(reasons));out.put("nextAction",action);
            out.put("diagnostics",Map.of("scope","RECALLED_CANDIDATES","counts",new LinkedHashMap<>(counts),
                    "semantic",semantic,"keyword",keyword,"complete",!incomplete()));
            return out;
        }
    }

    private static String excerpt(String text){return text.substring(0,Math.min(160,text.length()));}
    private static Map<String,Object> result(String status,String mode,String message,RecommendationRequest preferences,List<Map<String,Object>> cards) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("status",status);out.put("retrievalMode",mode);
        out.put("message",message);out.put("preferences",preferences);out.put("courses",cards);out.put("ownedCourses",List.of());out.put("count",cards.size());
        return out;
    }
}
