package com.kzai.aigc.knowledge.embedding;

import java.util.List;

/**
 * 知识库向量化服务（M9）。写入与查询必须使用同一向量空间与维度。
 * 实现分为：
 * <ul>
 *   <li>{@code DASHSCOPE}：真实 DashScope Embedding（模型名经 kzai.aigc.embedding-model 配置，
 *       Key 经环境变量注入），维度以真实返回为准（运行时实测后写入 ES mapping，不写死）；</li>
 *   <li>{@code MOCK}：确定性哈希向量（显著标识），仅用于 ES 索引与检索协议链路测试，
 *       <b>不能证明真实语义检索效果</b>，测试与报告均单独标识。</li>
 * </ul>
 * 生成式聊天模型 qwen-plus 的普通文本回答绝不冒充 Embedding 向量。
 */
public interface KnowledgeEmbeddingService {

    /** 提供方标识：DASHSCOPE / MOCK */
    String provider();

    /** 向量维度（以真实/约定的返回为准；写入 ES mapping 与查询同源） */
    int dimensions();

    /** 单文本向量化（文档写入侧，DashScope text_type=document 默认） */
    float[] embed(String text);

    /**
     * M15 REAL：用户查询向量化（检索侧）。
     * 与写入侧同一模型/同一维度/同一向量空间，仅 text_type=query 优化「查找」方向性；
     * 默认实现回退 {@link #embed(String)}（Mock 等无 query/document 区分的实现）。
     */
    default float[] embedQuery(String text) {
        return embed(text);
    }

    /** 批量向量化（顺序与入参一致） */
    List<float[]> embedAll(List<String> texts);

    /** 是否为 Mock（显著标识用） */
    boolean mock();
}
