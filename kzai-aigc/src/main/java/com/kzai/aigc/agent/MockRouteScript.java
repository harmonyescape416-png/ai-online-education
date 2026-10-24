package com.kzai.aigc.agent;

import com.kzai.aigc.tools.PurchaseProposalStore;

import java.util.List;

/**
 * MOCK 路由脚本（M8，仅 MOCK Provider 生效）。
 * <p>
 * <b>定位</b>：真实模型的意图识别由 RouteAgent 非流式模型调用完成；
 * 无 Key 环境用确定性关键词脚本验证「路由→分发→专业 Agent→SSE」的协议链路。
 * 脚本结果在审计与报告中显著标识为 MOCK，<b>不计入真实模型意图识别准确率</b>。
 * <p>
 * 分类规则（顺序判定，命中即返回）：
 * <ol>
 *   <li>已确认的购买提案 + 确认类短语 → BUY（确认续轮）；</li>
 *   <li>购买/下单意图 → BUY；</li>
 *   <li>找课/推荐意图 → RECOMMEND；</li>
 *   <li>价格/章节/详情/购买方式/学习问题 → CONSULT；</li>
 *   <li>明确技术知识提问（什么是/区别/如何理解…）→ KNOWLEDGE；</li>
 *   <li>其余（寒暄、普通陈述）→ OTHER（直接回复，与 M6 行为一致）。</li>
 * </ol>
 */
public final class MockRouteScript {

    private static final List<String> BUY_WORDS = List.of("购买", "买下", "下单", "订购");
    private static final List<String> RECOMMEND_HINTS = List.of("推荐", "找一下", "找出", "搜索", "查询", "有哪些",
            "有什么课", "看看课", "哪门课", "有没有", "入门", "实战", "进阶");
    private static final List<String> CONSULT_WORDS = List.of("价格", "多少钱", "多少章", "几章", "章节", "多少个章节", "详情",
            "适合", "适合什么", "零基础", "购买方式", "如何购买", "怎么购买", "无法学习", "为什么学不了", "有效期",
            "包含哪些", "讲什么", "主要讲", "是免费的吗", "免费的吗", "内容");
    /** 平台知识库 FAQ 词（原始文档域：购买规则/FAQ 类；M12 追加证书/发票/证书补发/设备词） */
    private static final List<String> FAQ_WORDS = List.of("退款", "发票", "规则", "faq",
            "证书", "补发", "折算", "几台", "多久能收到");
    /** 技术知识词（讲解类） */
    private static final List<String> TECH_WORDS = List.of("什么是", "区别", "如何理解", "讲讲", "解释一下",
            "概念", "原理", "多态", "微服务", "spring boot", "rag", "微调", "继承", "封装", "多线程");
    private static final List<String> CONFIRM_WORDS = List.of("确认", "确定", "好的", "是的", "同意", "下单吧", "购买吧", "可以");

    private MockRouteScript() {
    }

    public static RouteResult classify(String userText, PurchaseProposalStore.Proposal proposal) {
        long t0 = System.currentTimeMillis();
        String text = userText == null ? "" : userText.trim().toLowerCase();

        // 1) 已确认提案 + 确认短语 → BUY 续轮（状态按 userId+sessionId 隔离，不跨会话污染）
        if (proposal != null && proposal.confirmed && isConfirmPhrase(text)) {
            return RouteResult.ofAgent(AgentTypeEnum.BUY, "MOCK_CONFIRM", cost(t0));
        }
        // 2) 知识库 FAQ 意图（退款/发票/规则等平台知识问法优先于购买动词，如「购买规则是什么」）
        if (FAQ_WORDS.stream().anyMatch(text::contains)) {
            return RouteResult.ofAgent(AgentTypeEnum.KNOWLEDGE, "MOCK_KNOWLEDGE", cost(t0));
        }
        // 3) 购买意图
        if (BUY_WORDS.stream().anyMatch(text::contains)) {
            return RouteResult.ofAgent(AgentTypeEnum.BUY, "MOCK_BUY", cost(t0));
        }
        if(text.matches(".*(预算|每周|换一批|重新推荐).*"))
            return RouteResult.ofAgent(AgentTypeEnum.RECOMMEND,"MOCK_RECOMMEND_CONDITIONS",cost(t0));
        // 4) 找课/推荐
        if (text.contains("课程") || text.contains("课") || looksLikeSearch(text)) {
            if (RECOMMEND_HINTS.stream().anyMatch(text::contains)) {
                return RouteResult.ofAgent(AgentTypeEnum.RECOMMEND, "MOCK_RECOMMEND", cost(t0));
            }
        }
        // 5) 课程咨询
        if (CONSULT_WORDS.stream().anyMatch(text::contains)) {
            return RouteResult.ofAgent(AgentTypeEnum.CONSULT, "MOCK_CONSULT", cost(t0));
        }
        // 6) 技术知识（需含问询动词或长文本，避免普通陈述误判）
        if (TECH_WORDS.stream().anyMatch(text::contains)
                && (asksQuestion(text) || text.codePointCount(0, text.length()) > 80)) {
            return RouteResult.ofAgent(AgentTypeEnum.KNOWLEDGE, "MOCK_KNOWLEDGE", cost(t0));
        }
        // 7) 其余 → OTHER（直接回复）
        return RouteResult.direct(cost(t0), "MOCK_OTHER");
    }

    /** 与 MockToolScriptService 严格确认短语保持一致语义 */
    private static boolean isConfirmPhrase(String text) {
        return text.matches("^(请|帮我|麻烦)?[吧,，。！!\\s]*(好的?|是的?|确认(购买|下单)?|确定(购买|下单)?|同意|下单吧|购买吧|可以)[吧,，。！!\\s]*(下单|购买)?[吧,，。！!\\s]*$");
    }

    private static boolean looksLikeSearch(String text) {
        return text.matches(".*(找|推荐|搜索|查询).*") || text.matches(".*(java|spring|python|ai|mysql|redis).*");
    }

    private static boolean asksQuestion(String text) {
        return text.contains("？") || text.contains("?") || text.contains("什么")
                || text.contains("怎么") || text.contains("如何") || text.contains("讲讲")
                || text.contains("解释") || text.contains("理解");
    }

    private static long cost(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
