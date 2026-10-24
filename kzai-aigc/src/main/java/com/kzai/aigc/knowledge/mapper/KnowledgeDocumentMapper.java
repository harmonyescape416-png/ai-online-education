package com.kzai.aigc.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * 发布条件更新。测试把 #{ } 换成 ? 后用 JDBC 执行同一条语句。
     * 占位符顺序：chars、chunks、indexVersion、now、id、generation。
     */
    String PUBLISH_IF_CURRENT = """
            UPDATE ai_knowledge_document
            SET char_count = #{chars},
                chunk_count = #{chunks},
                index_version = #{indexVersion},
                index_status = 4,
                fail_reason = NULL,
                update_time = #{now}
            WHERE id = #{id}
              AND deleted = 0
              AND review_status = 1
              AND publish_generation = #{generation}
            """;

    /**
     * 只写本次索引结果。审核状态、访问级别和轮次不在 SET 里，避免旧对象把撤回覆盖回去。
     * 影响行数为 0 表示文档已删除、已撤回或审核轮次已经变了。
     */
    @Update(PUBLISH_IF_CURRENT)
    int publishIfCurrent(@Param("chars") int chars, @Param("chunks") int chunks,
                         @Param("indexVersion") int indexVersion, @Param("now") LocalDateTime now,
                         @Param("id") Long id, @Param("generation") int generation);
}
