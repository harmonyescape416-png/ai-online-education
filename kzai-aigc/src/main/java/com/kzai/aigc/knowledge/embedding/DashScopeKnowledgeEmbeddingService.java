package com.kzai.aigc.knowledge.embedding;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实 DashScope Embedding（M9）：模型名可配置（kzai.aigc.embedding-model，
 * 默认 text-embedding-v1；候选 text-embedding-v4 为北京地域当前官方推荐），
 * Key 经环境变量注入（spring.ai.dashscope.chat.api-key=${DASHSCOPE_API_KEY:}，与真实模型同源）。
 * 维度以真实调用返回为准（首次调用实测后由调用方写入 ES mapping，不写死）。
 * M15 REAL 修复：条件注解原读 kzai.ai.knowledge.embedding-provider（死键，本地配置
 * 静默失效），统一为 kzai.aigc.embedding-provider（AiProperties 同源，yml/env 均可生效）。
 * 条件：kzai.aigc.embedding-provider=dashscope 且 api-key 非空。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kzai.aigc", name = "embedding-provider",
        havingValue = "dashscope")
public class DashScopeKnowledgeEmbeddingService implements KnowledgeEmbeddingService {

    /** 历史缺省模型（保留常量供文档/日志引用）；当前模型经 kzai.aigc.embedding-model 配置 */
    public static final String DEFAULT_MODEL = "text-embedding-v1";

    private final DashScopeEmbeddingModel embeddingModel;
    private final DashScopeEmbeddingModel queryEmbeddingModel;
    private final String model;
    private volatile Integer cachedDimensions;
    /** M15.9：Embedding 调用超时（秒）——M6.1 DashScopeApi 内部 RestClient 无超时配置，
     * 以受控包装实现 fail-fast（瞬时网络故障快速降级，不挂死评测/检索链路） */
    private static final int EMBED_TIMEOUT_SECONDS = 15;
    private final java.util.concurrent.ExecutorService embedExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dashscope-embed");
                t.setDaemon(true);
                return t;
            });

    public DashScopeKnowledgeEmbeddingService(
            @Value("${spring.ai.dashscope.chat.api-key:}") String apiKey,
            @Value("${spring.ai.dashscope.chat.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${kzai.aigc.embedding-model:text-embedding-v1}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "embedding-provider=dashscope 但 DASHSCOPE_API_KEY 为空：真实向量化不可用，"
                            + "请配置 Key 或改用 mock（mock 结果不计入真实语义检索验收）");
        }
        this.model = model;
        // 显式 HTTP 实现和连接/读取时限，避免默认 JDK HTTP 客户端在本机出现 TLS 握手失败。
        // 五参构造顺序已核对当前依赖字节码：baseUrl、apiKey、RestClient、WebClient、errorHandler。
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(10000);
        var api = new DashScopeApi(baseUrl, apiKey,
                org.springframework.web.client.RestClient.builder().requestFactory(requestFactory),
                org.springframework.web.reactive.function.client.WebClient.builder(),
                org.springframework.ai.retry.RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        // M6.1 字节码实证：mergeOptions 的 textType 只取 defaultOptions（运行期传入被忽略），
        // 因此 query/document 区分必须用两个默认 textType 不同的实例，而非运行期 options。
        this.embeddingModel = new DashScopeEmbeddingModel(
                api, org.springframework.ai.document.MetadataMode.EMBED,
                DashScopeEmbeddingOptions.builder().withModel(model).build());
        this.queryEmbeddingModel = new DashScopeEmbeddingModel(
                api, org.springframework.ai.document.MetadataMode.EMBED,
                DashScopeEmbeddingOptions.builder().withModel(model).withTextType("query").build());
        log.info("[knowledge.embedding] 已启用真实 DashScope Embedding（model={}）", model);
    }

    @Override
    public String provider() {
        return "DASHSCOPE";
    }

    @Override
    public int dimensions() {
        if (cachedDimensions == null) {
            // 维度以真实返回为准（EmbeddingModel.dimensions() 会触发一次真实调用）
            cachedDimensions = embeddingModel.dimensions();
            log.info("[knowledge.embedding] 实测向量维度={}（model={}）", cachedDimensions, model);
        }
        return cachedDimensions;
    }

    @Override
    public float[] embed(String text) {
        // 文档写入：text_type=document（DashScope 默认，无需显式传）
        return embedWithTimeout(() -> embeddingModel.embed(text));
    }

    /** M15.9：受控超时包装（单次调用，无自动重试——重试由上层评测 retry 机制显式控制） */
    private float[] embedWithTimeout(java.util.function.Supplier<float[]> call) {
        var future = embedExecutor.submit(call::get);
        try {
            return future.get(EMBED_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            future.cancel(true);
            throw new IllegalStateException("Embedding 调用超时（>" + EMBED_TIMEOUT_SECONDS + "s）", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause() instanceof RuntimeException re ? re : ex;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Embedding 调用失败", cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 调用被中断", ex);
        }
    }

    /**
     * M15 REAL：用户查询向量化，text_type=query（官方建议：短查询匹配长文档场景，
     * query 向量更具「查找」方向性；与写入侧 document 向量同一模型同一维度同一向量空间）。
     * M6.1 实证：mergeOptions 的 textType 只取 defaultOptions，故用 query 专用实例。
     */
    @Override
    public float[] embedQuery(String text) {
        return embedWithTimeout(() -> queryEmbeddingModel.embed(text));
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        // M15 REAL：单次请求批量（texts 数组直传 API）。v4 批次上限 10 行/请求——
        // 调用方按 ≤10 条分批，本方法一条请求完成一批；顺序与入参一致。
        if (texts.isEmpty()) {
            return List.of();
        }
        var resp = embeddingModel.call(new org.springframework.ai.embedding.EmbeddingRequest(texts, null));
        List<float[]> out = new ArrayList<>(texts.size());
        for (var result : resp.getResults()) {
            out.add(result.getOutput());
        }
        if (out.size() != texts.size()) {
            throw new IllegalStateException("批量向量化返回数量不一致: expect=" + texts.size() + " got=" + out.size());
        }
        return out;
    }

    @Override
    public boolean mock() {
        return false;
    }
}
