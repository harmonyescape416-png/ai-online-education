package com.kzai.aigc.knowledge.embedding;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15 REAL 离线回归：锁定 Spring AI Alibaba 1.0.0-M6.1 DashScope Embedding 的请求形态。
 * <p>
 * 全程不联网（dummy Key + 反射调用包私有 createRequest），防止依赖升级悄悄改变请求契约：
 * <ul>
 *   <li>模型名以字符串直传请求体（M6.1 枚举仅 V1/V2/V3，但无枚举强转——v3/v4 可传）；</li>
 *   <li>parameters 仅含 textType——<b>M6.1 不支持 dimension 参数</b>，
 *       因此 v4/v3 只能用默认维度（1024），v1/v2 固定 1536；</li>
 *   <li>运行期 EmbeddingRequest.options 经 mergeOptions 生效（query/document 区分可用）。</li>
 * </ul>
 * 切换 text-embedding-v4 前必须用本测试 + 单次真实调用（授权后）双重验证。
 */
class DashScopeEmbeddingRequestShapeTest {

    /** 构造模型并返回其 defaultOptions（与生产 DashScopeKnowledgeEmbeddingService 同形态） */
    private static Object[] newModel(String model) {
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel(model).build();
        // dummy Key：createRequest 不发起网络请求
        DashScopeEmbeddingModel m = new DashScopeEmbeddingModel(
                new DashScopeApi("sk-dummy-for-shape-test"), MetadataMode.EMBED, options);
        return new Object[]{m, options};
    }

    /** 反射调用包私有 createRequest(EmbeddingRequest, DashScopeEmbeddingOptions) */
    private static String createRequest(DashScopeEmbeddingModel m,
                                        EmbeddingRequest req,
                                        DashScopeEmbeddingOptions options) throws Exception {
        Method method = DashScopeEmbeddingModel.class.getDeclaredMethod(
                "createRequest", EmbeddingRequest.class, DashScopeEmbeddingOptions.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(m, req, options));
    }

    /** 反射调用包私有 mergeOptions（call() 的实际路径：运行期 options 合并默认 options） */
    private static DashScopeEmbeddingOptions merge(DashScopeEmbeddingModel m,
                                                   org.springframework.ai.embedding.EmbeddingOptions runtime,
                                                   DashScopeEmbeddingOptions defaults) throws Exception {
        Method method = DashScopeEmbeddingModel.class.getDeclaredMethod(
                "mergeOptions", org.springframework.ai.embedding.EmbeddingOptions.class,
                DashScopeEmbeddingOptions.class);
        method.setAccessible(true);
        return (DashScopeEmbeddingOptions) method.invoke(m, runtime, defaults);
    }

    @Test
    void v1模型名直传请求体且parameters无dimension() throws Exception {
        Object[] pair = newModel("text-embedding-v1");
        String json = createRequest((DashScopeEmbeddingModel) pair[0],
                new EmbeddingRequest(List.of("hi"), null), (DashScopeEmbeddingOptions) pair[1]);
        assertTrue(json.contains("text-embedding-v1"), "请求体应含模型名: " + json);
        assertFalse(json.contains("\"dimension\""), "M6.1 parameters 不支持 dimension 字段: " + json);
    }

    @Test
    void v4模型名可直传请求体() throws Exception {
        // M6.1 EmbeddingModel 为 String 直传（无枚举校验）——v4 可传，真机维度需单次调用实测
        Object[] pair = newModel("text-embedding-v4");
        String json = createRequest((DashScopeEmbeddingModel) pair[0],
                new EmbeddingRequest(List.of("hi"), null), (DashScopeEmbeddingOptions) pair[1]);
        assertTrue(json.contains("text-embedding-v4"), "请求体应含 v4 模型名: " + json);
    }

    @Test
    void textType只来自defaultOptions运行期传入被忽略() throws Exception {
        // M6.1 字节码锁定：mergeOptions 的 textType 只取 defaultOptions（aload_2），
        // 运行期传入被忽略——query/document 区分必须用双实例（生产实现如此），防止未来误改
        Object[] pair = newModel("text-embedding-v4");
        DashScopeEmbeddingModel m = (DashScopeEmbeddingModel) pair[0];
        EmbeddingRequest req = new EmbeddingRequest(List.of("hi"),
                DashScopeEmbeddingOptions.builder().withModel("text-embedding-v4")
                        .withTextType("query").build());
        DashScopeEmbeddingOptions merged = merge(m, req.getOptions(), (DashScopeEmbeddingOptions) pair[1]);
        String json = createRequest(m, req, merged);
        assertFalse(json.contains("query"), "M6.1 运行期 textType 不应合入请求: " + json);
    }

    @Test
    void query专用实例默认textType合入请求() throws Exception {
        // 生产 embedQuery 形态：构造期 textType=query 的专用实例
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel("text-embedding-v4").withTextType("query").build();
        DashScopeEmbeddingModel m = new DashScopeEmbeddingModel(
                new DashScopeApi("sk-dummy-for-shape-test"), MetadataMode.EMBED, options);
        String json = createRequest(m, new EmbeddingRequest(List.of("hi"), null), options);
        assertTrue(json.contains("query"), "query 专用实例应携带 text_type=query: " + json);
    }
}
