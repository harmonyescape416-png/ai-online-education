package com.kzai.aigc.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.kzai.aigc.tools.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.DefaultFunctionCallbackResolver;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Spring AI ChatClient 装配（M6 手工构造，M8 修复工具执行装配）。
 * <p>
 * 背景（DashScopeAutoConfiguration 内含 Rerank/Embedding/Image 等无关模块，
 * 缺 Key 时全部启动失败）：在 application.yml 中整体 exclude 该自动装配，
 * 按需手工构建 ChatModel。
 * <p>
 * M8 修复（字节码核验 spring-ai-core 1.0.0-M6 AbstractToolCallSupport）：
 * <ul>
 *   <li>工具 schema 发送与执行解析都来自模型构造期的
 *       {@code functionCallbackRegister = merge(defaultOptions, toolFunctionCallbacks)}，
 *       运行期 options 中的回调只提供<b>名字</b>；</li>
 *   <li>因此必须用 5 参构造把业务工具回调注册进模型（否则真实模型工具执行时报
 *       "No function callback found"）；</li>
 *   <li>各 Agent 的工具白名单仍由运行期 options 的回调子集控制
 *       （按名字从 register 解析，白名单 ⊆ register）；</li>
 *   <li>proxyToolCalls 必须为 FALSE（缺省），TRUE 时模型不执行工具、只回传 ToolCall 元数据。</li>
 * </ul>
 * 启用条件：DASHSCOPE_ENABLED=true 且 DASHSCOPE_API_KEY 非空。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "kzai.aigc", name = "real-enabled", havingValue = "true")
public class AiModelConfig {

    @Value("${spring.ai.dashscope.chat.api-key:}")
    private String apiKey;

    @Value("${spring.ai.dashscope.chat.base-url:https://dashscope.aliyuncs.com}")
    private String baseUrl;

    @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String model;

    @Bean
    public DashScopeApi dashScopeApi() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("kzai.aigc.real-enabled=true 但 DASHSCOPE_API_KEY 为空");
        }
        // M15 REAL 验证：使用官方 1 参构造（默认 https://dashscope.aliyuncs.com 北京地域）。
        // 多参构造（apiKey, baseUrl, builders...）在本版本（1.0.0-M6.1）存在 baseUrl 错位缺陷——
        // 实测导致 RestClient/WebClient 请求 "URI with undefined scheme"（Spring AI 侧相对路径未拼接），
        // 已在独立 JVM 复现并确认；换 1 参构造后真实调用成功（qwen-plus 回复 + 真实 usage）。
        if (!"https://dashscope.aliyuncs.com".equals(baseUrl)) {
            log.warn("DASHSCOPE_BASE_URL={} 非默认值，但当前 Spring AI Alibaba 版本多参构造存在缺陷，已回退默认北京地域 endpoint", baseUrl);
        }
        return new DashScopeApi(apiKey);
    }

    @Bean
    public DashScopeChatModel dashScopeChatModel(DashScopeApi api, ToolRegistry toolRegistry) {
        DashScopeChatOptions options = DashScopeChatOptions.builder().withModel(model).build();
        // 业务工具回调注册进模型 register（M8 修复）；resolver 兜底仅用于未注册名报错可读
        List<FunctionCallback> toolCallbacks = toolRegistry.callbacks();
        DashScopeChatModel chatModel = new DashScopeChatModel(api, options,
                new DefaultFunctionCallbackResolver(), toolCallbacks, RetryTemplate.builder().build());
        log.info("kzai-aigc 已启用 DashScope ChatModel（真实模型 {}，注册业务工具：{}）",
                model, toolCallbacks.stream().map(FunctionCallback::getName).toList());
        return chatModel;
    }

    @Bean
    public ChatClient dashScopeChatClient(DashScopeChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * M15 REAL 修复：BUY 评测 Dry-run 专用 ChatClient 工厂。
     * <p>
     * 字节码核验（1.0.0-M6.1）：AbstractToolCallSupport.resolveFunctionCallbacks 是
     * <b>register 优先</b>（构造期注册的真实 createPreOrder 有真实下单副作用），
     * 运行期 options 的回调经 ModelOptionsUtils.copyToTarget（Jackson JSON 复制）无法存活，
     * 无法实现「同名运行期覆盖」。因此 Dry-run 评测必须用受控回调独立构造
     * ChatModel/ChatClient——register 中只有 Dry-run 回调，天然零副作用。
     */
    public interface DryRunChatClientFactory {
        ChatClient create(List<FunctionCallback> dryRunCallbacks);
    }

    @Bean
    public DryRunChatClientFactory dryRunChatClientFactory(DashScopeApi api) {
        return dryRunCallbacks -> {
            DashScopeChatModel dryRunModel = new DashScopeChatModel(api,
                    DashScopeChatOptions.builder().withModel(model).build(),
                    new DefaultFunctionCallbackResolver(), dryRunCallbacks,
                    RetryTemplate.builder().build());
            return ChatClient.builder(dryRunModel).build();
        };
    }

    /**
     * M15 REAL 修复：真实模型流式链路在 Netty 事件循环线程内联执行工具回调，
     * 默认 BlockingLoadBalancerClient 的 Mono.block() 在 NonBlocking 线程被
     * Reactor 3.6 BlockingChecks 拒绝（工具审计 success=false/SERVICE_UNAVAILABLE）。
     * 换用 DiscoveryClient 阻塞式实现，全程无 Reactor。
     */
    @Bean
    public DiscoveryBlockingLoadBalancerClient loadBalancerClient(
            org.springframework.cloud.client.discovery.DiscoveryClient discoveryClient) {
        return new DiscoveryBlockingLoadBalancerClient(discoveryClient);
    }
}
