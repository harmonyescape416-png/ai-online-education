package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.agent.AgentTypeEnum;
import com.kzai.aigc.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * MOCK 工具脚本（M7→M9，仅 MOCK Provider 且 mock-tool-script=true 时启用）。
 * <p>
 * <b>定位与红线</b>：真实模型的 Tool Calling 由模型自主发起（DashScopeChatProvider）；
 * MOCK 模式没有模型能力，为了验证「工具链路 + 1003 卡片 + 提案确认 + 幂等 + RAG 来源」的
 * <b>系统协议</b>，本服务按 RouteAgent 的路由结果触发<b>完全相同的工具实现</b>
 * （CourseTools / OrderTools / KnowledgeTools / PurchaseProposalStore / ToolResultHolder），
 * 不另行伪造第二套逻辑。所有回复显著标注【MOCK】；
 * 脚本化工具流<b>不计入</b>真实模型 Tool Calling / 真实语义检索验收。
 * <p>
 * M9：分支严格跟随 RouteAgent 路由结果（KNOWLEDGE→知识检索、BUY→预下单流程），
 * 避免脚本二次自检与路由分歧。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockToolScriptService {

    /** 严格确认短语：整句匹配才允许触发已确认提案（防止把新的购买描述误判为确认） */
    private static final String CONFIRM_STRICT =
            "^(请|帮我|麻烦)?[吧,，。！!\\s]*(好的?|是的?|确认(购买|下单)?|确定(购买|下单)?|同意|下单吧|购买吧|可以)[吧,，。！!\\s]*(下单|购买)?[吧,，。！!\\s]*$";

    private final CourseTools courseTools;
    private final OrderTools orderTools;
    private final KnowledgeTools knowledgeTools;
    private final PurchaseProposalStore proposalStore;
    private final AiProperties props;
    private final ObjectMapper objectMapper;
    private final com.kzai.aigc.recommendation.RecommendationTools recommendationTools;

    /**
     * 尝试脚本化工具流；未命中任何工具意图时返回 null（调用方回退到普通 MOCK 回复）。
     *
     * @param routedAgent RouteAgent 的路由结果（跟随分发，不二次自检）
     */
    public Flux<String> tryStream(String userText, ToolContext toolContext, long userId, String sessionId,
                                  AgentTypeEnum routedAgent) {
        String text = userText == null ? "" : userText.trim();
        String keyword = extractKeyword(text);

        // 1) 确认购买：仅当存在「已确认」提案（确认标记只能由 ChatService preScan 依据用户原文写入）
        PurchaseProposalStore.Proposal proposal = proposalStore.get(userId, sessionId);
        if (proposal != null && proposal.confirmed && text.matches(CONFIRM_STRICT)) {
            String reply = executeCreate(toolContext, proposal.courseIds.get(0));
            return stream(reply);
        }

        // 2) 跟随路由结果选择分支
        if (routedAgent == AgentTypeEnum.KNOWLEDGE) {
            return knowledgeBranch(userText, toolContext);
        }
        if (routedAgent == AgentTypeEnum.BUY) {
            return buyBranch(userText, toolContext);
        }
        if (routedAgent == AgentTypeEnum.CONSULT && keyword != null
                && text.matches(".*(价格|多少钱|多少章|几章|章节|详情).*")) {
            return detailBranch(keyword, toolContext);
        }
        if (routedAgent == AgentTypeEnum.RECOMMEND) {
            var request=new com.kzai.aigc.recommendation.RecommendationRequest();
            if(keyword!=null) { request.setGoal(text); request.setKeyword(keyword); }
            else if(!text.matches(".*(预算|每周|改成|换一批).*"))request.setGoal(text);
            if(text.contains("零基础"))request.setFoundation("BEGINNER");
            var budget=java.util.regex.Pattern.compile("预算[^0-9]{0,5}([0-9]+(?:\\.[0-9]{1,2})?)").matcher(text);
            if(budget.find())try {request.setBudgetCents(new java.math.BigDecimal(budget.group(1)).multiply(java.math.BigDecimal.valueOf(100)).intValueExact());}catch(ArithmeticException ignored){}
            String recommended=recommendationTools.recommend(request,toolContext);
            JsonNode result=parse(recommended);
            if(result!=null) {
                return stream(mockReply(com.kzai.aigc.recommendation.RecommendationReplyPolicy.render(
                        objectMapper.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){}))));
            }
            // 兼容旧协议单测替身；实际装配的推荐工具总会返回结构化结果。
            if(keyword==null)return null;
            return recommendBranch(keyword, toolContext);
        }

        // 3) 兜底：路由为 OTHER / 未命中分支 → 普通回复（M6 行为）
        return null;
    }

    // ------------------------------------------------------------------
    // 分支实现（全部走真实工具实现）
    // ------------------------------------------------------------------

    /** M9 知识检索：真实 KnowledgeTools（授权/阈值/来源卡片同真实链路） */
    private Flux<String> knowledgeBranch(String userText, ToolContext toolContext) {
        KnowledgeTools.KnowledgeSearchRequest req = new KnowledgeTools.KnowledgeSearchRequest();
        req.setQuestion(userText);
        String result = knowledgeTools.searchKnowledge(req, toolContext);
        JsonNode k = parse(result);
        String status = k == null ? "" : textOf(k, "status");
        if ("OK".equals(status) && k.withArray("hits").size() > 0) {
            JsonNode top = k.withArray("hits").get(0);
            StringBuilder sb = new StringBuilder("根据知识库「").append(textOf(top, "docTitle"))
                    .append("」：").append(abbreviate(textOf(top, "text"), 160));
            if (k.withArray("hits").size() > 1) {
                sb.append("（另有 ").append(k.withArray("hits").size() - 1).append(" 条相关来源）");
            }
            sb.append("。来源信息见下方引用卡片。");
            return stream(mockReply(sb.toString()));
        }
        if ("DEGRADED".equals(status) || "KNOWLEDGE_SERVICE_UNAVAILABLE".equals(status)) {
            return stream(mockReply("知识库服务暂时不可用，请稍后再试。"));
        }
        return stream(mockReply("知识库中没有找到与问题相关的片段。请换个问法，或确认知识库已上传并发布相关文档。"));
    }

    /** 购买流程：搜索 → createPreOrder（未确认时工具返回 CONFIRM_REQUIRED 并登记提案） */
    private Flux<String> buyBranch(String userText, ToolContext toolContext) {
        String keyword = extractKeyword(userText);
        if (keyword == null) {
            return stream(mockReply("请告诉我想购买的课程名称。"));
        }
        String found = searchWithFallback(toolContext, keyword, 3);
        JsonNode foundJson = parse(found);
        if (foundJson == null || !"OK".equals(textOf(foundJson, "status"))
                || foundJson.withArray("courses").size() == 0) {
            return stream(mockReply(textOf(foundJson, "message").isEmpty()
                    ? "未找到相关课程。" : textOf(foundJson, "message")));
        }
        JsonNode first = foundJson.withArray("courses").get(0);
        String courseId = textOf(first, "courseId");
        String name = textOf(first, "name");
        String priceYuan = textOf(first, "priceYuan");
        boolean purchasable = first.path("purchasable").asBoolean(false);
        if (!purchasable) {
            return stream(mockReply("已为您找到课程「" + name + "」，但该课程当前不可购买（"
                    + textOf(first, "reason") + "），暂无法为您创建订单。"));
        }
        String orderResult = orderTools.createPreOrder(preReq(courseId), toolContext);
        JsonNode orderJson = parse(orderResult);
        String status = orderJson == null ? "" : textOf(orderJson, "status");
        if ("CONFIRM_REQUIRED".equals(status)) {
            return stream(mockReply("已为您找到课程「" + name + "」，价格为 ¥" + priceYuan
                    + "。请确认是否创建<b>待支付</b>订单？确认后才会真实下单（支付仍需您在订单页完成）。"));
        }
        if ("OK".equals(status)) {
            return stream(mockReply("已按您的确认创建待支付订单：订单号 " + textOf(orderJson, "orderId")
                    + "，金额 ¥" + textOf(orderJson, "totalAmountYuan")
                    + "。订单尚未支付，请在「我的订单」完成支付。"));
        }
        return stream(mockReply("预下单未成功：" + (orderJson == null ? "交易服务异常" : textOf(orderJson, "message"))
                + " 未创建订单，请稍后重试。"));
    }

    /** 课程详情（CONSULT） */
    private Flux<String> detailBranch(String keyword, ToolContext toolContext) {
        String found = searchWithFallback(toolContext, keyword, 1);
        JsonNode foundJson = parse(found);
        if (foundJson != null && "OK".equals(textOf(foundJson, "status"))
                && foundJson.withArray("courses").size() > 0) {
            String courseId = textOf(foundJson.withArray("courses").get(0), "courseId");
            String detail = courseTools.getCourseDetail(detailReq(courseId), toolContext);
            JsonNode d = parse(detail);
            if (d != null && "OK".equals(textOf(d, "status"))) {
                return stream(mockReply("课程「" + textOf(d, "name") + "」：价格 ¥" + textOf(d, "priceYuan")
                        + "（" + intOf(d, "priceCents") + " 分），共 " + intOf(d, "chapterCount") + " 个章节。"
                        + (d.path("purchasable").asBoolean(false) ? "当前可购买。" : "当前不可购买：" + textOf(d, "reason"))));
            }
        }
        return stream(mockReply("未找到该课程或课程暂不可见。请确认课程名称后再试。"));
    }

    /** 课程推荐（RECOMMEND） */
    private Flux<String> recommendBranch(String keyword, ToolContext toolContext) {
        String found = searchWithFallback(toolContext, keyword, props.getToolSearchLimit());
        JsonNode foundJson = parse(found);
        if (foundJson != null && "OK".equals(textOf(foundJson, "status"))) {
            StringBuilder sb = new StringBuilder("已为您查询到 " + intOf(foundJson, "count") + " 门与「"
                    + keyword + "」相关的课程：\n");
            for (JsonNode c : foundJson.withArray("courses")) {
                sb.append("- ").append(textOf(c, "name")).append("  ¥").append(textOf(c, "priceYuan")).append("\n");
            }
            sb.append("卡片信息如下，点击可查看课程详情。");
            return stream(mockReply(sb.toString()));
        }
        if (foundJson != null && "NOT_FOUND".equals(textOf(foundJson, "status"))) {
            return stream(mockReply("未找到与「" + keyword + "」相关的已上架课程。您可以换个关键词试试。"));
        }
        return stream(mockReply("课程服务暂时不可用，请稍后重试。"));
    }

    // ------------------------------------------------------------------

    private Flux<String> stream(String reply) {
        String[] parts = reply.split("(?<=[。！？!?\\n])");
        return Flux.fromArray(parts)
                .filter(s -> !s.isEmpty())
                .delayElements(Duration.ofMillis(Math.max(props.getMockDelayMs(), 1)));
    }

    private String mockReply(String body) {
        return "【MOCK · 脚本化工具流（非真实模型）】" + body;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private CourseSearchRequest searchReq(String keyword, Integer limit) {
        CourseSearchRequest r = new CourseSearchRequest();
        r.setKeyword(keyword);
        r.setLimit(limit);
        return r;
    }

    private CourseDetailRequest detailReq(String courseId) {
        CourseDetailRequest r = new CourseDetailRequest();
        r.setCourseId(courseId);
        return r;
    }

    private PreOrderRequest preReq(String courseId) {
        PreOrderRequest r = new PreOrderRequest();
        r.setCourseId(courseId);
        return r;
    }

    private String executeCreate(ToolContext toolContext, String courseId) {
        String result = orderTools.createPreOrder(preReq(courseId), toolContext);
        JsonNode json = parse(result);
        if (json != null && "OK".equals(textOf(json, "status"))) {
            return mockReply("已按您的确认创建待支付订单：订单号 " + textOf(json, "orderId")
                    + "，金额 ¥" + textOf(json, "totalAmountYuan") + "。订单尚未支付，请在「我的订单」完成支付。"
                    + (Boolean.TRUE.equals(json.path("idempotentHit").asBoolean(false)) ? "（幂等命中：返回既有订单，未重复创建）" : ""));
        }
        return mockReply("预下单未成功：" + (json == null ? "交易服务异常" : textOf(json, "message")));
    }

    /**
     * 确定性关键词提取（MOCK 脚本专用）：去掉意图词与标点后取剩余主体。
     * 这是脚本启发式，不代表模型抽取能力。
     */
    static String extractKeyword(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll(
                "[帮请我你想]?(帮|帮我|我想|想|请)?(推荐|找一下?|找出?|搜索|查询|查查|看看|了解|介绍|购买|买下?|下单|订购|多少钱|价格是多少|价格|多少章|几章|有哪些|有什么|章节数?|详情|一下|一门|一个|相关|适合|课程|课|的|吗|呢|吧|？|\\?|。|，|,|、)+",
                "").trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 30 ? t.substring(0, 30) : t;
    }

    /**
     * 搜索降级：把关键词按「连续 CJK / 连续字母数字」分词，优先取最长的 CJK 词，
     * 否则取最长词。用于首次 LIKE 未命中时的确定性重试（MOCK 脚本，非模型能力）。
     */
    static String fallbackKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]+|[A-Za-z0-9]+")
                .matcher(keyword);
        String bestCjk = null;
        String bestAny = null;
        while (m.find()) {
            String token = m.group();
            if (bestAny == null || token.length() > bestAny.length()) {
                bestAny = token;
            }
            if (token.matches("[\\u4e00-\\u9fa5]+")) {
                if (bestCjk == null || token.length() > bestCjk.length()) {
                    bestCjk = token;
                }
            }
        }
        String pick = bestCjk != null && bestCjk.length() >= 2 ? bestCjk : bestAny;
        if (pick == null || pick.equals(keyword.trim())) {
            return null; // 无更短词可降级
        }
        return pick;
    }

    /** 带降级重试的搜索：首次未命中时用更短词重试一次（同一工具语义，结果如实返回） */
    private String searchWithFallback(ToolContext ctx, String keyword, Integer limit) {
        String first = courseTools.searchCourses(searchReq(keyword, limit), ctx);
        JsonNode json = parse(first);
        if (json != null && "NOT_FOUND".equals(textOf(json, "status"))) {
            String fallback = fallbackKeyword(keyword);
            if (fallback != null) {
                return courseTools.searchCourses(searchReq(fallback, limit), ctx);
            }
        }
        return first;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static long intOf(JsonNode node, String field) {
        if (node == null) {
            return 0;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? 0 : v.asLong();
    }

    /** 供 ChatService 判断「确认类短语」（与提案确认联动） */
    public static boolean isStrictConfirmation(String text) {
        return text != null && text.trim().matches(CONFIRM_STRICT);
    }
}
