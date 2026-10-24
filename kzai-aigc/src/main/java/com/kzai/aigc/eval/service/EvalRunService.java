package com.kzai.aigc.eval.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.eval.entity.AiEvalCase;
import com.kzai.aigc.eval.entity.AiEvalResult;
import com.kzai.aigc.eval.entity.AiEvalRun;
import com.kzai.aigc.eval.mapper.AiEvalCaseMapper;
import com.kzai.aigc.eval.mapper.AiEvalResultMapper;
import com.kzai.aigc.eval.mapper.AiEvalRunMapper;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.provider.ProviderException;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测批次服务（M12）：创建（防并发重复）、进度查询、失败重试（新 attempt 行）。
 * <p>
 * REAL 模式在缺 DASHSCOPE_API_KEY 时<b>显式不可执行</b>（批次状态=5 不可执行，
 * 不自动退化 MOCK、不产生伪造真实结果）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunService {

    public static final String MODE_MOCK = "MOCK";
    public static final String MODE_REAL = "REAL";

    private final AiEvalRunMapper runMapper;
    private final AiEvalResultMapper resultMapper;
    private final AiEvalCaseMapper caseMapper;
    private final ChatProvider chatProvider;
    private final KnowledgeEmbeddingService embedding;
    private final EsKnowledgeGateway es;
    private final EvalAnswerRunner answerRunner;
    private final EvalRunRunner runner;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value(
            "${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String dashscopeModel;

    @Transactional
    public Map<String, Object> create(String name, String execMode, List<Long> caseIdFilter) {
        if (!MODE_MOCK.equals(execMode) && !MODE_REAL.equals(execMode)) {
            throw new BizException("执行模式必须为 MOCK 或 REAL");
        }
        List<AiEvalCase> cases = caseIdFilter == null || caseIdFilter.isEmpty()
                ? caseMapper.selectList(Wrappers.<AiEvalCase>lambdaQuery().orderByAsc(AiEvalCase::getId))
                : caseMapper.selectList(Wrappers.<AiEvalCase>lambdaQuery()
                        .in(AiEvalCase::getId, caseIdFilter).orderByAsc(AiEvalCase::getId));
        if (cases.isEmpty()) {
            throw new BizException("没有可执行的评测用例");
        }
        if (cases.size() != (caseIdFilter == null || caseIdFilter.isEmpty() ? cases.size() : caseIdFilter.size())) {
            throw new BizException("部分选定的用例不存在");
        }

        AiEvalRun run = new AiEvalRun();
        run.setName(name == null || name.isBlank() ? "评测批次 " + LocalDateTime.now() : name.trim());
        run.setExecMode(execMode);
        run.setStatus(AiEvalRun.STATUS_RUNNING);
        LocalDateTime now = LocalDateTime.now();
        run.setCreateTime(now);
        run.setUpdateTime(now);
        run.setStartTime(now);
        run.setExecutorId(UserContext.getUserId());
        run.setTotalCases(cases.size());
        run.setSuccessCount(0);
        run.setFailedCount(0);
        run.setErrorCount(0);
        run.setSkippedCount(0);
        run.setCaseIds(writeJson(cases.stream().map(AiEvalCase::getId).toList()));

        // 配置快照（批次与结果可追溯：不同模型/Prompt/知识库版本不混淆）
        run.setProvider(chatProvider.code());
        run.setModelCode(chatProvider.mock() ? "MOCK" : dashscopeModel);
        run.setEmbeddingProvider(embedding.provider().toUpperCase());
        run.setKbPhysicalIndex(es.currentAliasedIndex());
        run.setPromptSnapshot(answerRunner.promptSnapshot());

        // REAL 模式显式不可执行（不得自动退化 MOCK 冒充真实评测）
        if (MODE_REAL.equals(execMode) && chatProvider.mock()) {
            run.setStatus(AiEvalRun.STATUS_NOT_EXECUTABLE);
            run.setError("REAL 模式不可执行：未配置 DASHSCOPE_API_KEY（真实模型/真实 Embedding 未就绪）。"
                    + "本次未执行任何评测，也不产生伪造的真实结果。");
            run.setEndTime(now);
            try {
                runMapper.insert(run);
            } catch (DuplicateKeyException ex) {
                throw new BizException("已有运行中的评测批次，请等待完成后再创建");
            }
            return vo(run);
        }

        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException ex) {
            // active_flag 生成列唯一约束：全局至多一个运行中批次（防重复点击）
            throw new BizException("已有运行中的评测批次，请等待完成后再创建");
        }
        long runId = run.getId();
        // 事务提交后再异步执行（TransactionTemplate 语义下本方法返回即提交）
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runner.submit(runId);
                    }
                });
        return vo(run);
    }

    /** 失败用例重试：仅对最新 attempt 为「执行失败/超时」的用例，产生新 attempt 行（历史可追溯） */
    @Transactional
    public Map<String, Object> retryFailed(long runId) {
        AiEvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("批次不存在");
        }
        if (run.getStatus() == AiEvalRun.STATUS_RUNNING) {
            throw new BizException("批次仍在运行中，不能重试");
        }
        if (run.getStatus() == AiEvalRun.STATUS_NOT_EXECUTABLE) {
            throw new BizException("REAL 模式未配置，无可重试内容");
        }
        // 最新 attempt 中执行失败/超时的用例
        List<AiEvalResult> results = resultMapper.selectList(Wrappers.<AiEvalResult>lambdaQuery()
                .eq(AiEvalResult::getRunId, runId));
        Map<Long, AiEvalResult> latest = new LinkedHashMap<>();
        for (AiEvalResult r : results) {
            latest.merge(r.getCaseId(), r, (a, b) ->
                    (a.getAttempt() == null ? 0 : a.getAttempt()) >= (b.getAttempt() == null ? 0 : b.getAttempt()) ? a : b);
        }
        List<Long> retryIds = latest.values().stream()
                .filter(r -> r.getStatus() != null && (r.getStatus() == AiEvalResult.ST_EXEC_FAILED
                        || r.getStatus() == AiEvalResult.ST_TIMEOUT))
                .map(AiEvalResult::getCaseId).toList();
        if (retryIds.isEmpty()) {
            throw new BizException("没有可重试的失败用例");
        }
        run.setStatus(AiEvalRun.STATUS_RUNNING);
        run.setCaseIds(writeJson(retryIds));
        run.setError("重试 " + retryIds.size() + " 条失败用例（新 attempt 行，历史保留）");
        run.setEndTime(null);
        run.setUpdateTime(LocalDateTime.now());
        runMapper.updateById(run);
        Long rid = run.getId();
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runner.submit(rid);
                    }
                });
        return Map.of("runId", String.valueOf(runId), "retryCases", retryIds.size());
    }

    public Map<String, Object> get(long runId) {
        AiEvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("批次不存在");
        }
        Map<String, Object> m = vo(run);
        // 进度 = 结果表中该批次非待执行行数（含重试，按用例最新 attempt）
        List<AiEvalResult> results = resultMapper.selectList(Wrappers.<AiEvalResult>lambdaQuery()
                .eq(AiEvalResult::getRunId, runId));
        Map<Long, AiEvalResult> latest = new LinkedHashMap<>();
        for (AiEvalResult r : results) {
            latest.merge(r.getCaseId(), r, (a, b) ->
                    (a.getAttempt() == null ? 0 : a.getAttempt()) >= (b.getAttempt() == null ? 0 : b.getAttempt()) ? a : b);
        }
        long done = latest.values().stream().filter(r -> r.getStatus() != null
                && r.getStatus() != AiEvalResult.ST_PENDING).count();
        m.put("doneCases", done);
        m.put("mock", chatProvider.mock());
        m.put("realAvailable", !chatProvider.mock());
        return m;
    }

    public Map<String, Object> list(long page, long size) {
        Page<AiEvalRun> p = runMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 50)),
                Wrappers.<AiEvalRun>lambdaQuery().orderByDesc(AiEvalRun::getCreateTime));
        return Map.of("total", p.getTotal(),
                "records", p.getRecords().stream().map(this::vo).toList());
    }

    /** 批次结果列表（每用例最新 attempt；可按状态过滤；attemptHistory=true 返回全部行） */
    public Map<String, Object> results(long runId, Integer statusFilter, boolean allAttempts, long page, long size) {
        if (runMapper.selectById(runId) == null) {
            throw new BizException("批次不存在");
        }
        List<AiEvalResult> results = resultMapper.selectList(Wrappers.<AiEvalResult>lambdaQuery()
                .eq(AiEvalResult::getRunId, runId)
                .eq(statusFilter != null, AiEvalResult::getStatus, statusFilter)
                .orderByAsc(AiEvalResult::getCaseId)
                .orderByAsc(AiEvalResult::getAttempt));
        List<AiEvalResult> rows;
        if (allAttempts) {
            rows = results;
        } else {
            Map<Long, AiEvalResult> latest = new LinkedHashMap<>();
            for (AiEvalResult r : results) {
                latest.merge(r.getCaseId(), r, (a, b) ->
                        (a.getAttempt() == null ? 0 : a.getAttempt()) >= (b.getAttempt() == null ? 0 : b.getAttempt()) ? a : b);
            }
            rows = new ArrayList<>(latest.values());
        }
        return Map.of("total", rows.size(), "records", rows.stream().map(this::resultVO).toList());
    }

    // ------------------------------------------------------------------

    private Map<String, Object> vo(AiEvalRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(run.getId()));
        m.put("name", run.getName());
        m.put("execMode", run.getExecMode());
        m.put("status", run.getStatus());
        m.put("provider", run.getProvider());
        m.put("modelCode", run.getModelCode());
        m.put("embeddingProvider", run.getEmbeddingProvider());
        m.put("kbPhysicalIndex", run.getKbPhysicalIndex());
        m.put("promptSnapshot", run.getPromptSnapshot());
        m.put("totalCases", run.getTotalCases());
        m.put("successCount", run.getSuccessCount());
        m.put("failedCount", run.getFailedCount());
        m.put("errorCount", run.getErrorCount());
        m.put("skippedCount", run.getSkippedCount());
        m.put("executorId", run.getExecutorId() == null ? null : String.valueOf(run.getExecutorId()));
        m.put("error", run.getError());
        m.put("createTime", run.getCreateTime());
        m.put("startTime", run.getStartTime());
        m.put("endTime", run.getEndTime());
        return m;
    }

    private Map<String, Object> resultVO(AiEvalResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.getId()));
        m.put("runId", String.valueOf(r.getRunId()));
        m.put("caseId", String.valueOf(r.getCaseId()));
        m.put("attempt", r.getAttempt());
        m.put("status", r.getStatus());
        m.put("category", r.getCategory());
        m.put("expectedIntent", r.getExpectedIntent());
        m.put("expectedDocId", r.getExpectedDocId());
        m.put("actualIntent", r.getActualIntent());
        m.put("routeLatencyMs", r.getRouteLatencyMs());
        m.put("actualDocIds", r.getActualDocIds());
        m.put("ragHit", r.getRagHit());
        m.put("ragEmptyRecall", r.getRagEmptyRecall());
        m.put("ragLatencyMs", r.getRagLatencyMs());
        m.put("answer", r.getAnswer());
        m.put("answerLatencyMs", r.getAnswerLatencyMs());
        m.put("latencyMs", r.getLatencyMs());
        m.put("promptTokens", r.getPromptTokens());
        m.put("completionTokens", r.getCompletionTokens());
        m.put("totalTokens", r.getTotalTokens());
        m.put("tokenSource", r.getTokenSource());
        m.put("refusal", r.getRefusal());
        m.put("refusalRule", r.getRefusalRule());
        m.put("hasCitation", r.getHasCitation());
        m.put("leakSuspected", r.getLeakSuspected());
        m.put("writeToolAttempt", r.getWriteToolAttempt());
        m.put("passed", r.getPassed());
        m.put("verdictRule", r.getVerdictRule());
        m.put("failReason", r.getFailReason());
        m.put("provider", r.getProvider());
        m.put("modelCode", r.getModelCode());
        m.put("error", r.getError());
        m.put("createTime", r.getCreateTime());
        return m;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception ex) {
            throw new BizException("配置序列化失败");
        }
    }
}
