package com.kzai.aigc.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 知识库文档（M9，sql/10_m9_knowledge.sql）。
 * 审核状态：0待审核 1已发布 2已撤回——仅「已发布」可被 RAG 检索（检索时以本表实时状态为权威）。
 */
@Data
@TableName("ai_knowledge_document")
public class KnowledgeDocument {

    public static final int INDEX_PENDING = 0;
    public static final int INDEX_PARSING = 1;
    public static final int INDEX_CHUNKED = 2;
    public static final int INDEX_INDEXING = 3;
    public static final int INDEX_SUCCESS = 4;
    public static final int INDEX_FAILED = 5;

    public static final int REVIEW_PENDING = 0;
    public static final int REVIEW_PUBLISHED = 1;
    public static final int REVIEW_WITHDRAWN = 2;

    public static final String TYPE_PDF = "PDF";
    public static final String TYPE_MARKDOWN = "MARKDOWN";

    public static final String ACCESS_PUBLIC = "PUBLIC";
    public static final String ACCESS_PROTECTED = "PROTECTED";
    public static final String ACCESS_INTERNAL = "INTERNAL";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String title;
    private String docType;
    private Long courseId;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String sha256;
    private Integer charCount;
    private Integer chunkCount;
    private Integer indexVersion;
    private Integer indexStatus;
    private String failReason;
    private String accessLevel;
    private Integer reviewStatus;
    /** 每次审核发布或撤回加一。旧索引任务只允许发布自己记录的这一轮。 */
    private Integer publishGeneration;
    private Long reviewer;
    private LocalDateTime reviewTime;
    private Long publisher;
    /** 教师资料来源。管理员直接上传时为空。 */
    private Long sourceResourceId;
    /** 资源不可变版本。替换会生成新资源 ID，因此当前版本等于资源 ID。 */
    private String sourceVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
