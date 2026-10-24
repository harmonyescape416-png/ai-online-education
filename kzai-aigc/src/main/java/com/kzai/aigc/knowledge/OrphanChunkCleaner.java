package com.kzai.aigc.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 孤儿切片定时补偿清理（M11 重建原子性策略 C 后半：重建前清理 + 定时补偿）。
 * <p>
 * 版本化发布流程在「写新版本切片 → 发布切换」之间中断时，会留下未发布切片。
 * 这些切片不会参与检索（检索按 MySQL 权威发布版本过滤），但会占用索引空间，
 * 因此由本组件定时兜底清理：保留 {@code index_version} 等于权威版本的切片，
 * 删除其余（未发布/历史残留）。ES 不可用时跳过本轮，不影响业务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanChunkCleaner {

    private static final int BATCH_LIMIT = 50;

    private final KnowledgeDocumentMapper documentMapper;
    private final EsKnowledgeGateway es;

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void clean() {
        List<KnowledgeDocument> published;
        try {
            published = documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getIndexStatus, KnowledgeDocument.INDEX_SUCCESS)
                    .isNotNull(KnowledgeDocument::getIndexVersion)
                    .last("LIMIT " + BATCH_LIMIT));
        } catch (RuntimeException ex) {
            log.warn("[knowledge.orphan] 查询已索引文档失败，跳过本轮清理: {}", ex.getMessage());
            return;
        }
        int cleaned = 0;
        for (KnowledgeDocument doc : published) {
            try {
                long removed = es.deleteByDocIdVersionNeq(doc.getId(), doc.getIndexVersion());
                if (removed > 0) {
                    cleaned += (int) removed;
                    log.info("[knowledge.orphan] 清理未发布切片 doc={} keepVer={} removed={}",
                            doc.getId(), doc.getIndexVersion(), removed);
                }
            } catch (RuntimeException ex) {
                log.warn("[knowledge.orphan] 清理失败 doc={}: {}", doc.getId(), ex.getMessage());
            }
        }
        if (cleaned > 0) {
            log.info("[knowledge.orphan] 本轮清理孤儿切片 {} 片", cleaned);
        }
    }
}