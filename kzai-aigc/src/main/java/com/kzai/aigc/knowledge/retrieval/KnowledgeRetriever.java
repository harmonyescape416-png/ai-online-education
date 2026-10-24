package com.kzai.aigc.knowledge.retrieval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.aigc.client.TradeClient;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识检索器（M9）：真实 ES kNN + 授权与状态过滤。
 * <p>
 * 检索口径：
 * <ul>
 *   <li>TopK 默认 6（≤20）；相似度阈值 0.6——cosine 归一化口径，ES _score ∈ [0,1] 直接可比；</li>
 *   <li>ES 层预过滤：reviewStatus=1（已发布）+ 访问级别/课程候选集（快照，用于缩小候选）；</li>
 *   <li><b>MySQL 实时复核为权威</b>：已发布、未删除、访问级别、PROTECTED 课程的权益
 *       （TradeClient /internal/trade/entitlements，fail-closed——查询失败按无权益处理）、
 *       INTERNAL 仅管理员；复核通过才计入结果，直到取满 topK 或候选耗尽；</li>
 *   <li>ES 不可用返回降级状态（degraded=true，不假装检索完成）；空结果返回空列表及说明。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetriever {

    private final EsKnowledgeGateway es;
    private final KnowledgeEmbeddingService embedding;
    private final KnowledgeDocumentMapper documentMapper;
    private final TradeClient tradeClient;
    private final AiProperties props;
    private final com.kzai.aigc.config.DynamicAiParams dynamic; // M13：运行时动态参数（TopK/阈值热更新）
    // M15 REAL：REAL Embedding 模式下的索引防混用守卫（fail-closed；Mock 模式无此 Bean）
    private final org.springframework.beans.factory.ObjectProvider<com.kzai.aigc.knowledge.index.RealIndexGuard> indexGuard;

    public static class RetrievalResult {
        public final List<Map<String, Object>> hits = new ArrayList<>();
        public boolean degraded;
        public String degradeReason;

        public static RetrievalResult degraded(String reason) {
            RetrievalResult r = new RetrievalResult();
            r.degraded = true;
            r.degradeReason = reason;
            return r;
        }
    }

    /**
     * 以可信用户上下文检索（userId/roles 来自网关验证后的认证上下文，非模型输入）。
     */
    public RetrievalResult retrieve(String question, long userId, List<String> roles, Integer topKOverride) {
        int topK = topKOverride == null ? dynamic.topK()
                : Math.max(1, Math.min(topKOverride, 20));
        if (question == null || question.isBlank()) {
            return RetrievalResult.degraded("EMPTY_QUESTION");
        }
        boolean admin = roles != null && (roles.contains("ADMIN") || roles.contains("STAFF"));
        // M15 REAL：REAL Embedding 模式下先做索引防混用校验（fail-closed——
        // 索引不属于 REAL 前缀/维度不匹配/Provider 为 MOCK 时拒绝检索，返回明确降级原因）
        com.kzai.aigc.knowledge.index.RealIndexGuard guard = indexGuard.getIfAvailable();
        if (guard != null) {
            var guardError = guard.checkReadable();
            if (guardError.isPresent()) {
                return RetrievalResult.degraded("REAL_INDEX_GUARD: " + guardError.get());
            }
        }
        float[] vector;
        try {
            // M15 REAL：查询侧走 embedQuery（DashScope text_type=query；Mock 实现等价回退）
            vector = embedding.embedQuery(question);
        } catch (RuntimeException ex) {
            log.warn("[knowledge.retrieval] 向量化失败: {}", ex.getMessage());
            return RetrievalResult.degraded("EMBEDDING_ERROR");
        }

        // ES 预过滤候选（快照；k 扩大 3 倍以补偿复核淘汰）
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("reviewStatus", 1);
        List<String> accessLevels = new ArrayList<>(List.of(KnowledgeDocument.ACCESS_PUBLIC));
        if (admin) {
            accessLevels.add(KnowledgeDocument.ACCESS_PROTECTED);
            accessLevels.add(KnowledgeDocument.ACCESS_INTERNAL);
        } else {
            accessLevels.add(KnowledgeDocument.ACCESS_PROTECTED);
        }
        filter.put("accessLevel", accessLevels);

        // M12：发布版本预过滤——以 MySQL 权威 (docId, index_version) 白名单约束 kNN 候选窗口，
        // 未发布/过期版本切片不占用 TopK 候选位（消除「无效版本挤占有效结果」的漏召回窗口）。
        // MySQL 逐条复核仍保留（删除/撤回/权益的实时权威，防御双保险）。
        List<Map<String, Object>> versionPairs = authoritativeVersionPairs();

        List<Map<String, Object>> candidates = es.knnSearch(vector, topK * 3, filter, versionPairs);
        if (candidates == null) {
            return RetrievalResult.degraded("ES_UNAVAILABLE");
        }

        RetrievalResult result = new RetrievalResult();
        double minScore = dynamic.minScore();
        Set<Long> seenDocs = new HashSet<>();
        Set<Long> entitledCourses = new HashSet<>();
        for (Map<String, Object> hit : candidates) {
            if (result.hits.size() >= topK) {
                break;
            }
            double score = ((Number) hit.getOrDefault("score", 0)).doubleValue();
            if (score < minScore) {
                continue; // 阈值过滤（归一化口径）
            }
            long docId = Long.parseLong(String.valueOf(hit.get("docId")));
            KnowledgeDocument doc = documentMapper.selectById(docId);
            // DB 权威复核：已发布 + 未删除（逻辑删除/撤回/下线立即不可召回）
            if (doc == null || doc.getDeleted() != null && doc.getDeleted() == 1) {
                continue;
            }
            if (doc.getReviewStatus() == null || doc.getReviewStatus() != KnowledgeDocument.REVIEW_PUBLISHED) {
                continue;
            }
            // M11 重建原子性：切片版本必须等于 MySQL 权威发布版本（未发布的新切片、
            // 以及任何中断残留的版本一律不可召回，避免新旧混用/部分覆盖）
            Object hitVersion = hit.get("indexVersion");
            if (doc.getIndexVersion() != null && hitVersion != null
                    && ((Number) hitVersion).intValue() != doc.getIndexVersion()) {
                continue;
            }
            String access = doc.getAccessLevel() == null ? KnowledgeDocument.ACCESS_PUBLIC : doc.getAccessLevel();
            if (KnowledgeDocument.ACCESS_INTERNAL.equals(access) && !admin) {
                continue; // 内部文档仅管理员
            }
            if (KnowledgeDocument.ACCESS_PROTECTED.equals(access)) {
                Long courseId = doc.getCourseId();
                if (courseId == null) {
                    continue;
                }
                // 权益校验：已购学员 / 教师（本人课程）/ 管理员；查询失败按无权益（fail-closed）
                if (!admin && !entitledCourses.contains(courseId) && !hasEntitlement(userId, courseId)) {
                    continue; // 未购学员不得获取受保护课程片段
                }
                entitledCourses.add(courseId);
            }
            // 命中
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", String.valueOf(docId));
            m.put("chunkIndex", hit.get("chunkIndex"));
            m.put("chunkId", hit.get("chunkId") == null
                    ? docId + "_" + hit.get("chunkIndex") : hit.get("chunkId"));
            m.put("indexVersion", hit.get("indexVersion")); // M12：发布版本随命中返回（评测与审计依据）
            m.put("docTitle", doc.getTitle());
            m.put("courseId", doc.getCourseId() == null ? null : String.valueOf(doc.getCourseId()));
            m.put("score", score);
            m.put("provider", embedding.provider());
            Object text = hit.get("text");
            String snippet = text == null ? "" : String.valueOf(text);
            m.put("snippet", snippet.length() > 300 ? snippet.substring(0, 300) + "…" : snippet);
            m.put("text", snippet);
            result.hits.add(m);
            seenDocs.add(docId);
        }
        return result;
    }

    /** 权益校验（fail-closed）：复用 M4 内部权益接口 */
    private boolean hasEntitlement(long userId, Long courseId) {
        try {
            R<Boolean> resp = tradeClient.hasEntitlement(userId, courseId);
            return resp != null && resp.isSuccess() && Boolean.TRUE.equals(resp.getData());
        } catch (RuntimeException ex) {
            log.warn("[knowledge.retrieval] 权益查询失败（按无权益处理）: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * MySQL 权威发布版本白名单（M12）：
     * 已发布未删除文档 → (docId, indexVersion) should 子句；indexVersion 为 NULL 的
     * 兼容数据仅约束 docId（任意版本可召回，由逐条复核兜底）。
     * 文档数超过 {@value #MAX_VERSION_PAIRS} 时放弃预过滤（回退 3 倍候选扩大 + 逐条复核），
     * 避免生成超大查询；本项目知识库规模远低于该上限。
     */
    private List<Map<String, Object>> authoritativeVersionPairs() {
        try {
            List<KnowledgeDocument> published = documentMapper.selectList(
                    Wrappers.<KnowledgeDocument>lambdaQuery()
                            .select(KnowledgeDocument::getId, KnowledgeDocument::getIndexVersion)
                            .eq(KnowledgeDocument::getDeleted, 0)
                            .eq(KnowledgeDocument::getReviewStatus, KnowledgeDocument.REVIEW_PUBLISHED));
            if (published.isEmpty()) {
                return List.of(); // 无有效文档：白名单为空 → knn filter 不放行任何切片
            }
            if (published.size() > MAX_VERSION_PAIRS) {
                log.warn("[knowledge.retrieval] 已发布文档数 {} 超过预过滤上限 {}，回退候选扩大口径",
                        published.size(), MAX_VERSION_PAIRS);
                return null;
            }
            List<Map<String, Object>> pairs = new ArrayList<>(published.size());
            for (KnowledgeDocument d : published) {
                if (d.getIndexVersion() == null) {
                    pairs.add(Map.of("term", Map.of("docId", d.getId())));
                } else {
                    pairs.add(Map.of("bool", Map.of("filter", List.of(
                            Map.of("term", Map.of("docId", d.getId())),
                            Map.of("term", Map.of("indexVersion", d.getIndexVersion()))))));
                }
            }
            return pairs;
        } catch (RuntimeException ex) {
            // MySQL 不可用等异常：不放大权限（预过滤失效），仍由逐条复核兜底
            log.warn("[knowledge.retrieval] 权威版本白名单加载失败（回退逐条复核）: {}", ex.getMessage());
            return null;
        }
    }

    /** 版本白名单子句上限（超过则回退旧口径，防御超大 should 查询） */
    private static final int MAX_VERSION_PAIRS = 500;
}
