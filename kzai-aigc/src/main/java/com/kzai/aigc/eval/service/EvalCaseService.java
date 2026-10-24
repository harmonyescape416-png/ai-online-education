package com.kzai.aigc.eval.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.aigc.eval.entity.AiEvalCase;
import com.kzai.aigc.eval.entity.AiEvalCaseMeta;
import com.kzai.aigc.eval.mapper.AiEvalCaseMapper;
import com.kzai.aigc.eval.mapper.AiEvalCaseMetaMapper;
import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测用例管理（M12）：查询、手工新增/编辑、CSV 导入、标注元数据维护。
 * <p>
 * 校验：问题 1–500 字非空；分类六类严格枚举；期望意图严格枚举或空；
 * CSV ≤2MB、表头精确匹配、字段长度、逐行校验并报告行号；重复规则=question 完全相同则拒绝该行。
 */
@Service
@RequiredArgsConstructor
public class EvalCaseService {

    public static final Set<String> CATEGORIES = Set.of(
            AiEvalCase.CAT_RECOMMEND, AiEvalCase.CAT_CONSULT, AiEvalCase.CAT_BUY,
            AiEvalCase.CAT_KNOWLEDGE, AiEvalCase.CAT_GREETING, AiEvalCase.CAT_OUT_OF_SCOPE);

    private static final Set<String> INTENTS = Set.of("RECOMMEND", "CONSULT", "BUY",
            "KNOWLEDGE", "GREETING", "OUT_OF_SCOPE", "OTHER");

    private final AiEvalCaseMapper caseMapper;
    private final AiEvalCaseMetaMapper metaMapper;
    private final EvalCsvSupport csv;

    public Map<String, Object> page(long page, long size, String category, String keyword) {
        Page<AiEvalCase> p = caseMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                Wrappers.<AiEvalCase>lambdaQuery()
                        .eq(StringUtils.hasText(category)
                                && CATEGORIES.contains(category.trim().toUpperCase()), AiEvalCase::getCategory,
                                category == null ? null : category.trim().toUpperCase())
                        .like(StringUtils.hasText(keyword), AiEvalCase::getQuestion,
                                keyword == null ? null : keyword.trim())
                        .orderByDesc(AiEvalCase::getCreateTime)
                        .orderByDesc(AiEvalCase::getId));
        List<Map<String, Object>> records = p.getRecords().stream().map(this::toVO).toList();
        return Map.of("total", p.getTotal(), "records", records);
    }

    public Map<String, Object> toVO(AiEvalCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(c.getId()));
        m.put("question", c.getQuestion());
        m.put("category", c.getCategory());
        m.put("expectedIntent", c.getExpectedIntent());
        m.put("expectedDocId", c.getExpectedDocId());
        m.put("createTime", c.getCreateTime());
        AiEvalCaseMeta meta = metaMapper.selectById(c.getId());
        if (meta != null) {
            m.put("shouldRefuse", meta.getShouldRefuse());
            m.put("needsCitation", meta.getNeedsCitation());
            m.put("expectTool", meta.getExpectTool());
            m.put("refuseReason", meta.getRefuseReason());
            m.put("note", meta.getNote());
            m.put("dataDeps", meta.getDataDeps());
            m.put("source", meta.getSource());
            m.put("isRegression", meta.getIsRegression());
        } else {
            m.put("shouldRefuse", 0);
            m.put("needsCitation", 0);
            m.put("isRegression", 0);
        }
        return m;
    }

    @Transactional
    public Map<String, Object> create(String question, String category, String expectedIntent,
                                      String expectedDocId, Integer shouldRefuse, Integer needsCitation,
                                      String expectTool, String refuseReason, String note,
                                      String dataDeps, String source, Integer isRegression) {
        validate(question, category, expectedIntent, expectedDocId);
        AiEvalCase c = new AiEvalCase();
        c.setQuestion(question.trim());
        c.setCategory(category.trim().toUpperCase());
        c.setExpectedIntent(expectedIntent == null || expectedIntent.isBlank()
                ? null : expectedIntent.trim().toUpperCase());
        c.setExpectedDocId(expectedDocId == null || expectedDocId.isBlank() ? null : expectedDocId.trim());
        c.setPassed(0);
        caseMapper.insert(c);
        upsertMeta(c.getId(), shouldRefuse, needsCitation, expectTool, refuseReason,
                note, dataDeps, source, isRegression);
        return toVO(caseMapper.selectById(c.getId()));
    }

    @Transactional
    public Map<String, Object> update(long id, String question, String category, String expectedIntent,
                                      String expectedDocId, Integer shouldRefuse, Integer needsCitation,
                                      String expectTool, String refuseReason, String note,
                                      String dataDeps, String source, Integer isRegression) {
        AiEvalCase c = caseMapper.selectById(id);
        if (c == null) {
            throw new BizException("评测用例不存在");
        }
        validate(question, category, expectedIntent, expectedDocId);
        c.setQuestion(question.trim());
        c.setCategory(category.trim().toUpperCase());
        c.setExpectedIntent(expectedIntent == null || expectedIntent.isBlank()
                ? null : expectedIntent.trim().toUpperCase());
        c.setExpectedDocId(expectedDocId == null || expectedDocId.isBlank() ? null : expectedDocId.trim());
        caseMapper.updateById(c);
        upsertMeta(id, shouldRefuse, needsCitation, expectTool, refuseReason,
                note, dataDeps, source, isRegression);
        return toVO(caseMapper.selectById(id));
    }

    @Transactional
    public Map<String, Object> delete(long id) {
        AiEvalCase c = caseMapper.selectById(id);
        if (c == null) {
            throw new BizException("评测用例不存在");
        }
        caseMapper.deleteById(id);
        metaMapper.deleteById(id);
        return Map.of("deleted", true, "id", String.valueOf(id));
    }

    /** 标注加入回归集（失败用例转回归） */
    @Transactional
    public Map<String, Object> addToRegression(long caseId) {
        AiEvalCase c = caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("评测用例不存在");
        }
        AiEvalCaseMeta meta = metaMapper.selectById(caseId);
        if (meta == null) {
            meta = new AiEvalCaseMeta();
            meta.setCaseId(caseId);
            meta.setShouldRefuse(0);
            meta.setNeedsCitation(0);
            meta.setNote("由失败用例转入回归集");
            meta.setSource("M12:失败用例转回归");
            meta.setCreateTime(LocalDateTime.now());
            meta.setIsRegression(1);
            meta.setUpdateTime(LocalDateTime.now());
            metaMapper.insert(meta);
        } else {
            meta.setIsRegression(1);
            meta.setUpdateTime(LocalDateTime.now());
            metaMapper.updateById(meta);
        }
        return Map.of("caseId", String.valueOf(caseId), "isRegression", 1);
    }

    /** 回归用例列表 */
    public List<Map<String, Object>> regressionCases() {
        List<Long> ids = metaMapper.selectList(Wrappers.<AiEvalCaseMeta>lambdaQuery()
                        .eq(AiEvalCaseMeta::getIsRegression, 1))
                .stream().map(AiEvalCaseMeta::getCaseId).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            AiEvalCase c = caseMapper.selectById(id);
            if (c != null) {
                out.add(toVO(c));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // CSV 导入
    // ------------------------------------------------------------------

    private static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;
    private static final List<String> IMPORT_HEADER = List.of(
            "question", "category", "expected_intent", "expected_doc_id",
            "should_refuse", "needs_citation", "expect_tool", "note", "data_deps");

    /**
     * CSV 导入：表头精确匹配（列顺序一致）；逐行校验；
     * 重复规则：question 与库中已有用例完全相同 → 该行拒绝并报告（不入库）。
     */
    @Transactional
    public Map<String, Object> importCsv(byte[] bytes, String source) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException("导入文件为空");
        }
        if (bytes.length > MAX_IMPORT_BYTES) {
            throw new BizException("导入文件超过 2MB 上限");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<List<String>> rows = csv.parse(text);
        if (rows.isEmpty()) {
            throw new BizException("CSV 无内容");
        }
        List<String> header = rows.get(0);
        if (!IMPORT_HEADER.equals(header)) {
            throw new BizException("CSV 表头必须为（逗号分隔）：" + String.join(",", IMPORT_HEADER));
        }
        int imported = 0;
        List<String> errors = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() == 1 && row.get(0).isBlank()) {
                continue; // 空行跳过
            }
            if (row.size() < IMPORT_HEADER.size()) {
                errors.add("第" + (i + 1) + "行：列数不足（" + row.size() + "/" + IMPORT_HEADER.size() + "）");
                continue;
            }
            String question = cell(row, 0);
            String category = cell(row, 1);
            String expectedIntent = cell(row, 2);
            String expectedDocId = cell(row, 3);
            String shouldRefuse = cell(row, 4);
            String needsCitation = cell(row, 5);
            String expectTool = cell(row, 6);
            String note = cell(row, 7);
            String dataDeps = cell(row, 8);
            try {
                validate(question, category, expectedIntent, expectedDocId);
                if (!shouldRefuse.isBlank() && !"0".equals(shouldRefuse.trim()) && !"1".equals(shouldRefuse.trim())) {
                    throw new BizException("should_refuse 只能为 0/1");
                }
                if (!needsCitation.isBlank() && !"0".equals(needsCitation.trim()) && !"1".equals(needsCitation.trim())) {
                    throw new BizException("needs_citation 只能为 0/1");
                }
                // 重复规则：question 完全相同 → 拒绝该行
                Long dup = caseMapper.selectCount(Wrappers.<AiEvalCase>lambdaQuery()
                        .eq(AiEvalCase::getQuestion, question.trim()));
                if (dup != null && dup > 0) {
                    duplicates.add("第" + (i + 1) + "行：question 与既有用例重复，已跳过");
                    continue;
                }
                create(question, category, expectedIntent, expectedDocId,
                        shouldRefuse.isBlank() ? 0 : Integer.parseInt(shouldRefuse.trim()),
                        needsCitation.isBlank() ? 0 : Integer.parseInt(needsCitation.trim()),
                        expectTool, null,
                        note.isBlank() ? "CSV 导入（来源：" + (source == null || source.isBlank() ? "admin" : source) + "）" : note,
                        dataDeps, "CSV导入:" + (source == null || source.isBlank() ? "admin" : source), 0);
                imported++;
            } catch (BizException ex) {
                errors.add("第" + (i + 1) + "行：" + ex.getMessage());
            } catch (NumberFormatException ex) {
                errors.add("第" + (i + 1) + "行：数值字段格式错误");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", imported);
        out.put("skipped", duplicates.size());
        out.put("errors", errors);
        out.put("duplicateDetails", duplicates);
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void upsertMeta(Long caseId, Integer shouldRefuse, Integer needsCitation, String expectTool,
                            String refuseReason, String note, String dataDeps, String source,
                            Integer isRegression) {
        AiEvalCaseMeta meta = metaMapper.selectById(caseId);
        boolean create = meta == null;
        if (create) {
            meta = new AiEvalCaseMeta();
            meta.setCaseId(caseId);
            meta.setCreateTime(LocalDateTime.now());
        }
        meta.setShouldRefuse(shouldRefuse != null && shouldRefuse == 1 ? 1 : 0);
        meta.setNeedsCitation(needsCitation != null && needsCitation == 1 ? 1 : 0);
        meta.setExpectTool(expectTool == null || expectTool.isBlank() ? null : expectTool.trim());
        meta.setRefuseReason(refuseReason == null || refuseReason.isBlank() ? null : refuseReason.trim());
        meta.setNote(note == null || note.isBlank() ? "未填写标注理由" : note.trim());
        meta.setDataDeps(dataDeps == null || dataDeps.isBlank() ? null : dataDeps.trim());
        meta.setSource(source == null || source.isBlank() ? "管理员手工标注" : source.trim());
        meta.setIsRegression(isRegression != null && isRegression == 1 ? 1 : 0);
        meta.setUpdateTime(LocalDateTime.now());
        if (create) {
            metaMapper.insert(meta);
        } else {
            metaMapper.updateById(meta);
        }
    }

    private void validate(String question, String category, String expectedIntent, String expectedDocId) {
        if (!StringUtils.hasText(question)) {
            throw new BizException("评测问题不能为空");
        }
        String q = question.trim();
        if (q.length() > 500) {
            throw new BizException("评测问题最长 500 字");
        }
        if (!StringUtils.hasText(category) || !CATEGORIES.contains(category.trim().toUpperCase())) {
            throw new BizException("非法分类：" + category + "（允许：" + String.join("/", CATEGORIES) + "）");
        }
        if (StringUtils.hasText(expectedIntent)
                && !INTENTS.contains(expectedIntent.trim().toUpperCase())) {
            throw new BizException("非法期望意图：" + expectedIntent);
        }
        if (StringUtils.hasText(expectedDocId) && expectedDocId.trim().length() > 64) {
            throw new BizException("expected_doc_id 最长 64 字符");
        }
    }

    private static String cell(List<String> row, int idx) {
        return idx < row.size() ? row.get(idx) : "";
    }
}
