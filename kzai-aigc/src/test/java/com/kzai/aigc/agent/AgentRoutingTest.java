package com.kzai.aigc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.prompt.AgentPromptSpec;
import com.kzai.aigc.prompt.NacosPromptGateway;
import com.kzai.aigc.prompt.PromptManager;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.provider.ProviderException;
import com.kzai.aigc.tools.CourseTools;
import com.kzai.aigc.tools.OrderTools;
import com.kzai.aigc.tools.PurchaseProposalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * M8 多智能体路由单元测试：意图分类、严格枚举、兜底、工具白名单矩阵、
 * Prompt 热更新/校验/降级（真实模型路由语义见 ProxyToolCallsRuntimeTest 与有 Key 验收）。
 */
class AgentRoutingTest {

    private ChatProvider provider;
    private RouteAgent routeAgent;
    private PurchaseProposalStore proposalStore;

    @BeforeEach
    void setUp() {
        provider = Mockito.mock(ChatProvider.class);
        when(provider.mock()).thenReturn(true);
        when(provider.code()).thenReturn("MOCK");
        proposalStore = Mockito.mock(PurchaseProposalStore.class);
        routeAgent = new RouteAgent(provider, promptManager(), proposalStore, new AiProperties());
    }

    private PromptManager promptManager() {
        NacosPromptGateway gateway = Mockito.mock(NacosPromptGateway.class);
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        return new PromptManager(gateway, redis, new ObjectMapper());
    }

    // ---------- 路由分类（MOCK 脚本：仅协议验证，显著标识） ----------

    @Test
    void recommendIntent_routedToRecommendAgent() {
        assertEquals(AgentTypeEnum.RECOMMEND,
                routeAgent.route(3L, "s1", "推荐适合零基础的 Java 课程", null).getAgent());
        assertEquals(AgentTypeEnum.RECOMMEND,
                routeAgent.route(3L, "s1", "有哪些 Spring Boot 课程？", null).getAgent());
    }

    @Test
    void consultIntent_routedToConsultAgent() {
        assertEquals(AgentTypeEnum.CONSULT,
                routeAgent.route(3L, "s1", "这门课程的价格是多少", null).getAgent());
        assertEquals(AgentTypeEnum.CONSULT,
                routeAgent.route(3L, "s1", "这门课适合零基础吗", null).getAgent());
        assertEquals(AgentTypeEnum.CONSULT,
                routeAgent.route(3L, "s1", "我的课程为什么无法学习", null).getAgent());
    }

    @Test
    void buyIntent_routedToBuyAgent() {
        assertEquals(AgentTypeEnum.BUY,
                routeAgent.route(3L, "s1", "帮我购买 Java 基础课程", null).getAgent());
    }

    @Test
    void confirmedProposal_withConfirmPhrase_continuesBuyAgent() {
        // 确认续轮：已确认提案（同会话）+ 严格确认短语 → BUY（复用 M7 提案，不重新下单）
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        assertEquals(AgentTypeEnum.BUY, routeAgent.route(3L, "s1", "确认", null).getAgent());
        // 未确认提案 + 确认短语 → 不进 BUY（原始要求：只有明确确认的提案可下单）
        p.confirmed = false;
        assertFalse(routeAgent.route(3L, "s1", "确认", null).getAgent() == AgentTypeEnum.BUY);
    }

    @Test
    void knowledgeIntent_routedToKnowledgeAgent() {
        assertEquals(AgentTypeEnum.KNOWLEDGE,
                routeAgent.route(3L, "s1", "什么是 Spring Boot？", null).getAgent());
        assertEquals(AgentTypeEnum.KNOWLEDGE,
                routeAgent.route(3L, "s1", "RAG 与微调有什么区别？", null).getAgent());
        assertEquals(AgentTypeEnum.KNOWLEDGE,
                routeAgent.route(3L, "s1", "如何理解 Java 多态？", null).getAgent());
    }

    @Test
    void greetingOrIrrelevant_directAnswer_noAgent() {
        RouteResult r1 = routeAgent.route(3L, "s1", "你好", null);
        assertTrue(r1.isDirectAnswer(), "寒暄应直接回复");
        assertNull(r1.getAgent());
        RouteResult r2 = routeAgent.route(3L, "s1", "流式中文测试", null);
        assertTrue(r2.isDirectAnswer(), "普通陈述按无关直接回复（与 M6 行为一致）");
    }

    // ---------- 真实模型语义：严格枚举 + 兜底 + 降级 ----------

    @Test
    void realModel_strictEnumParse() {
        when(provider.mock()).thenReturn(false);
        when(provider.call(any())).thenReturn("RECOMMEND");
        assertEquals(AgentTypeEnum.RECOMMEND, routeAgent.route(3L, "s1", "找课", null).getAgent());
        assertFalse(routeAgent.route(3L, "s1", "找课", null).isFallback());
    }

    @Test
    void realModel_unparseable_fallsBackToConsult() {
        when(provider.mock()).thenReturn(false);
        when(provider.call(any())).thenReturn("我觉得可能是课程吧");
        RouteResult r = routeAgent.route(3L, "s1", "随便说说", null);
        assertEquals(AgentTypeEnum.CONSULT, r.getAgent(), "解析失败必须兜底 ConsultAgent");
        assertTrue(r.isFallback());
        assertEquals("UNPARSEABLE", r.getErrorKind());
    }

    @Test
    void realModel_greetingEnum_directAnswer() {
        when(provider.mock()).thenReturn(false);
        when(provider.call(any())).thenReturn("OTHER");
        RouteResult r = routeAgent.route(3L, "s1", "你好呀", null);
        assertTrue(r.isDirectAnswer(), "OTHER/寒暄按原始要求直接回复");
        assertNull(r.getAgent());
    }

    @Test
    void realModel_timeout_fallsBackControlled() {
        when(provider.mock()).thenReturn(false);
        when(provider.call(any())).thenThrow(
                new ProviderException(ProviderException.Kind.TIMEOUT, "DASHSCOPE", "Read timed out", null));
        RouteResult r = routeAgent.route(3L, "s1", "找课", null);
        assertEquals(AgentTypeEnum.CONSULT, r.getAgent(), "路由超时必须受控降级，不阻塞用户");
        assertTrue(r.isFallback());
        assertEquals("TIMEOUT", r.getErrorKind());
    }

    // ---------- AgentToolPolicy：最小授权矩阵 ----------

    @Test
    void toolPolicy_matrix() {
        CourseTools courseTools = Mockito.mock(CourseTools.class);
        when(courseTools.callbacks()).thenReturn(List.of(
                namedCallback("searchCourses"), namedCallback("getCourseDetail")));
        OrderTools orderTools = Mockito.mock(OrderTools.class);
        when(orderTools.callbacks()).thenReturn(List.of(namedCallback("createPreOrder")));
        com.kzai.aigc.tools.KnowledgeTools knowledgeTools =
                Mockito.mock(com.kzai.aigc.tools.KnowledgeTools.class);
        when(knowledgeTools.callbacks()).thenReturn(List.of(namedCallback("searchKnowledge")));
        var recommendations = Mockito.mock(com.kzai.aigc.recommendation.RecommendationTools.class);
        when(recommendations.callbacks()).thenReturn(List.of(namedCallback("recommendCourses")));
        AgentToolPolicy policy = new AgentToolPolicy(courseTools, orderTools, knowledgeTools, recommendations);

        assertEquals(0, policy.callbacksFor(AgentTypeEnum.ROUTE).size(), "RouteAgent 无业务工具");
        // M9：KnowledgeAgent 仅持只读知识检索工具
        assertEquals(List.of("searchKnowledge"),
                policy.callbacksFor(AgentTypeEnum.KNOWLEDGE).stream()
                        .map(org.springframework.ai.model.function.FunctionCallback::getName).toList(),
                "KnowledgeAgent 仅授予只读知识检索工具");
        assertEquals(List.of("recommendCourses", "getCourseDetail"),
                policy.callbacksFor(AgentTypeEnum.RECOMMEND).stream()
                        .map(org.springframework.ai.model.function.FunctionCallback::getName).toList());
        assertEquals(List.of("searchCourses", "getCourseDetail"),
                policy.callbacksFor(AgentTypeEnum.CONSULT).stream()
                        .map(org.springframework.ai.model.function.FunctionCallback::getName).toList());
        List<String> buyTools = policy.callbacksFor(AgentTypeEnum.BUY)
                .stream().map(org.springframework.ai.model.function.FunctionCallback::getName).toList();
        assertEquals(List.of("searchCourses", "getCourseDetail", "createPreOrder"), buyTools,
                "仅 BuyAgent 可携带预下单工具（仍受确认门禁保护）");
    }

    private org.springframework.ai.model.function.FunctionCallback namedCallback(String name) {
        return org.springframework.ai.model.function.FunctionCallback.builder()
                .function(name, (java.util.function.BiFunction<java.util.Map, org.springframework.ai.chat.model.ToolContext, String>) (req, ctx) -> "ok")
                .description(name)
                .inputType(java.util.Map.class)
                .build();
    }

    // ---------- PromptManager：校验 / 热更新 / 降级 ----------

    @Test
    void promptManager_validatesAndRejectsInvalidConfig() {
        PromptManager pm = promptManager();
        assertTrue(pm.validate("{\"prompts\":{}}") != null, "空 prompts 必须拒绝");
        assertTrue(pm.validate("{\"prompts\":{\"ROUTE\":{\"systemPrompt\":\"x\"}}}") != null,
                "ROUTE 提示词缺枚举必须拒绝");
        assertTrue(pm.validate("{\"prompts\":{\"RECOMMEND\":{\"systemPrompt\":\"含 ${USER} 注入\"}}}") != null,
                "含 ${} 占位符必须拒绝（防配置注入）");
        assertTrue(pm.validate("not-json") != null);
        // 合法内容（含全部枚举）
        String ok = "{\"version\":\"v1\",\"prompts\":{\"ROUTE\":{\"systemPrompt\":\""
                + "RECOMMEND CONSULT BUY KNOWLEDGE OTHER\"}}}";
        assertNull(pm.validate(ok));
    }

    @Test
    void promptManager_hotUpdate_andFallback() {
        NacosPromptGateway gateway = Mockito.mock(NacosPromptGateway.class);
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        PromptManager pm = new PromptManager(gateway, redis, new ObjectMapper());
        // 初始：Nacos 不可用 → 内置默认
        when(gateway.fetch()).thenReturn(null);
        pm.init();
        assertEquals("BUILTIN", pm.get(AgentTypeEnum.ROUTE).getSource());

        // 发布成功（Nacos 写成功且回读一致）→ 立即生效 + Redis 缓存
        String content = "{\"version\":\"v9\",\"prompts\":{\"RECOMMEND\":{"
                + "\"systemPrompt\":\"你是推荐助手\"}}}";
        when(gateway.fetch()).thenReturn(content);
        when(gateway.publish(anyString(), anyString())).thenReturn(true);
        Mockito.when(redis.opsForValue())
                .thenReturn(Mockito.mock(org.springframework.data.redis.core.ValueOperations.class));
        assertNull(pm.applyFromPublish(content, "admin-1"),
                "发布应用必须成功（Nacos 写成功且回读一致）");
        assertEquals("NACOS", pm.get(AgentTypeEnum.RECOMMEND).getSource());
        assertEquals("v9", pm.get(AgentTypeEnum.RECOMMEND).getVersion());
        assertEquals("你是推荐助手", pm.get(AgentTypeEnum.RECOMMEND).getSystemPrompt());
        // 未覆盖的 Agent 沿用内置
        assertEquals("BUILTIN", pm.get(AgentTypeEnum.KNOWLEDGE).getSource());

        // Nacos 恢复为不可达 → 保留最后有效配置（refresh 不回退）
        when(gateway.fetch()).thenReturn(null);
        pm.refreshFromNacos();
        assertEquals("NACOS", pm.get(AgentTypeEnum.RECOMMEND).getSource());

        // 非法内容 → 拒绝应用
        when(gateway.fetch()).thenReturn("{\"prompts\":{\"RECOMMEND\":{\"systemPrompt\":\"\"}}}");
        pm.refreshFromNacos();
        assertEquals("你是推荐助手", pm.get(AgentTypeEnum.RECOMMEND).getSystemPrompt(),
                "非法配置必须拒绝并保留已验证版本");
    }
}
