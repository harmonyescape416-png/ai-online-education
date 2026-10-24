package com.kzai.aigc.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 评测用例标注扩展（M12，用户已批准，1:1 依附 ai_eval_case）。
 * 原始 11 字段无「应拒答/需引用/标注理由/数据依赖」载体，且语义不可挪用。
 */
@Data
@TableName("ai_eval_case_meta")
public class AiEvalCaseMeta {

    public static final String DEPS_ADMIN_TEST_ACCESS = "admin_test_access";

    @TableId(type = IdType.INPUT)
    private Long caseId;

    private Integer shouldRefuse;
    private Integer needsCitation;
    private String expectTool;
    private String refuseReason;
    private String note;
    private String dataDeps;
    private String source;
    private Integer isRegression;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
