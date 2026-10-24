package com.kzai.aigc.config;

import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * M15 REAL 模式混用防护：真实 ChatModel（real-enabled=true）与 MOCK Embedding
 * 的组合必须启动期显式警示——向量无语义，检索命中不可计为真实语义检索，
 * 绝不允许静默降级后仍标记 REAL RAG（RAG 来源卡片 provider=MOCK 可追溯）。
 * 以 embedding.mock() 运行时判定而非配置字符串比对，避免配置键漂移再次失配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingModeGuard {

    private final KnowledgeEmbeddingService embedding;

    @Value("${kzai.aigc.real-enabled:false}")
    private boolean realEnabled;

    @PostConstruct
    void check() {
        if (realEnabled && embedding.mock()) {
            log.warn("[M15 模式提示] 真实 ChatModel 已启用（REAL 对话/工具调用），"
                    + "但 Embedding Provider 仍为 MOCK（kzai.aigc.embedding-provider=mock）："
                    + "RAG 检索为无语义协议链路，检索命中与来源卡片不构成真实语义检索验收。"
                    + "切换真实 Embedding 前请先完成 REAL 索引隔离方案（独立前缀+维度校验）。");
        }
    }
}
