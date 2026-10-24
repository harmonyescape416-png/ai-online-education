package com.kzai.aigc.controller;

import com.kzai.aigc.eval.entity.AiEvalResult;
import com.kzai.aigc.eval.mapper.AiEvalResultMapper;
import com.kzai.aigc.eval.service.EvalCaseService;
import com.kzai.aigc.eval.service.EvalCsvSupport;
import com.kzai.aigc.eval.service.EvalMetricsService;
import com.kzai.aigc.eval.service.EvalRunService;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 批量评测管理（M12，管理员专用 @RequireRoles(ADMIN)）。
 * <p>
 * 接口已登记 docs/api-extension.md（M12）。普通用户/教师/未登录一律 401/403，
 * 不得读取评测数据或触发批量模型调用。
 */
@RestController
@RequestMapping("/eval")
@RequireRoles({Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class EvalAdminController {

    private final EvalCaseService caseService;
    private final EvalRunService runService;
    private final EvalMetricsService metricsService;
    private final EvalCsvSupport csv;
    private final AiEvalResultMapper resultMapper;
    private final com.kzai.aigc.supervision.OperationAuditBridge auditBridge; // M13 审计落库
    private final com.kzai.aigc.eval.mapper.AiEvalCaseMapper evalCaseMapper;

    // ------------------------------------------------------------------
    // 用例管理
    // ------------------------------------------------------------------

    @GetMapping("/cases")
    public R<Map<String, Object>> cases(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(caseService.page(page, size, category, keyword));
    }

    @PostMapping("/cases")
    public R<Map<String, Object>> createCase(@RequestBody Map<String, Object> body) {
        return R.ok(caseService.create(
                str(body.get("question")), str(body.get("category")),
                str(body.get("expectedIntent")), str(body.get("expectedDocId")),
                intOf(body.get("shouldRefuse")), intOf(body.get("needsCitation")),
                str(body.get("expectTool")), str(body.get("refuseReason")),
                str(body.get("note")), str(body.get("dataDeps")),
                str(body.get("source")), intOf(body.get("isRegression"))));
    }

    @PutMapping("/cases/{id}")
    public R<Map<String, Object>> updateCase(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return R.ok(caseService.update(id,
                str(body.get("question")), str(body.get("category")),
                str(body.get("expectedIntent")), str(body.get("expectedDocId")),
                intOf(body.get("shouldRefuse")), intOf(body.get("needsCitation")),
                str(body.get("expectTool")), str(body.get("refuseReason")),
                str(body.get("note")), str(body.get("dataDeps")),
                str(body.get("source")), intOf(body.get("isRegression"))));
    }

    @DeleteMapping("/cases/{id}")
    public R<Map<String, Object>> deleteCase(@PathVariable long id) {
        return R.ok(caseService.delete(id));
    }

    @PostMapping("/cases/import")
    public R<Map<String, Object>> importCases(@RequestParam("file") MultipartFile file) {
        try {
            return R.ok(caseService.importCsv(file.getBytes(), file.getOriginalFilename()));
        } catch (java.io.IOException ex) {
            throw new BizException("读取导入文件失败：" + ex.getMessage());
        }
    }

    @GetMapping("/cases/regression")
    public R<List<Map<String, Object>>> regressionCases() {
        return R.ok(caseService.regressionCases());
    }

    @PostMapping("/results/{id}/add-to-regression")
    public R<Map<String, Object>> addToRegression(@PathVariable long id) {
        AiEvalResult result = resultMapper.selectById(id);
        if (result == null) {
            throw new BizException("评测结果不存在");
        }
        return R.ok(caseService.addToRegression(result.getCaseId()));
    }

    // ------------------------------------------------------------------
    // 批量执行与历史
    // ------------------------------------------------------------------

    @PostMapping("/run")
    public R<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("caseIds");
        List<Long> caseIds = null;
        if (rawIds != null && !rawIds.isEmpty()) {
            caseIds = new ArrayList<>();
            for (Object o : rawIds) {
                caseIds.add(Long.parseLong(String.valueOf(o)));
            }
        }
        Map<String, Object> run = runService.create(str(body.get("name")), str(body.get("execMode")), caseIds);
        // M13：评测批次启动审计（含模式与用例数；不写用例正文）
        auditBridge.write(UserContext.getUserId(), UserContext.getUsername(), "EVAL", "EVAL_RUN_CREATE",
                "ai_eval_run", Long.parseLong(String.valueOf(run.get("id"))),
                "{\"execMode\":\"" + str(body.get("execMode")) + "\",\"totalCases\":"
                        + str(run.get("totalCases")) + ",\"status\":" + str(run.get("status")) + "}");
        return R.ok(run);
    }

    @GetMapping("/runs")
    public R<Map<String, Object>> runs(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return R.ok(runService.list(page, size));
    }

    @GetMapping("/runs/{runId}")
    public R<Map<String, Object>> run(@PathVariable long runId) {
        return R.ok(runService.get(runId));
    }

    @GetMapping("/runs/{runId}/results")
    public R<Map<String, Object>> results(@PathVariable long runId,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(defaultValue = "false") boolean allAttempts,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "100") long size) {
        return R.ok(runService.results(runId, status, allAttempts, page, size));
    }

    @GetMapping("/runs/{runId}/summary")
    public R<Map<String, Object>> summary(@PathVariable long runId) {
        return R.ok(metricsService.summary(runId));
    }

    /** 原始设计接口（5.2.3 评测统计）：默认返回最新「有结果数据」批次的汇总（跳过不可执行批次），可显式指定 runId */
    @GetMapping("/summary")
    public R<Map<String, Object>> summaryLatest(@RequestParam(required = false) Long runId) {
        long id = runId != null ? runId : 0;
        if (id <= 0) {
            // 倒序找第一个存在结果行的批次（跳过 REAL 未配置等不可执行批次）
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> runs =
                    (java.util.List<java.util.Map<String, Object>>) runService.list(1, 50).get("records");
            for (java.util.Map<String, Object> r : runs) {
                long rid = Long.parseLong(String.valueOf(r.get("id")));
                if (!resultMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiEvalResult>lambdaQuery()
                                .eq(AiEvalResult::getRunId, rid).last("LIMIT 1")).isEmpty()) {
                    id = rid;
                    break;
                }
            }
        }
        if (id <= 0) {
            throw new BizException("尚无可统计的评测批次");
        }
        return R.ok(metricsService.summary(id));
    }

    @PostMapping("/runs/{runId}/retry")
    public R<Map<String, Object>> retry(@PathVariable long runId) {
        return R.ok(runService.retryFailed(runId));
    }

    /** CSV 导出（UTF-8 BOM + 公式注入防护；含来源/模型/模式/失败原因） */
    @GetMapping("/runs/{runId}/export")
    public ResponseEntity<byte[]> export(@PathVariable long runId) {
        Map<String, Object> run = runService.get(runId);
        @SuppressWarnings("unchecked")
        Map<String, Object> rs = runService.results(runId, null, false, 1, 10_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rs.get("records");
        List<String> header = List.of("runId", "runMode", "provider", "modelCode", "caseId", "attempt",
                "question", "category", "expectedIntent", "expectedDocId", "actualIntent", "ragHit",
                "ragEmptyRecall", "actualDocIds", "refusal", "refusalRule", "hasCitation", "leakSuspected",
                "passed", "verdictRule", "failReason", "error",
                "latencyMs", "routeLatencyMs", "ragLatencyMs", "answerLatencyMs",
                "totalTokens", "tokenSource", "status");
        // question 批量映射（用例可能被删除，缺失时留空）
        Map<Long, String> questionMap = new java.util.HashMap<>();
        if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(r -> Long.parseLong(String.valueOf(r.get("caseId")))).toList();
            for (com.kzai.aigc.eval.entity.AiEvalCase c : evalCaseMapper.selectBatchIds(ids)) {
                questionMap.put(c.getId(), c.getQuestion());
            }
        }
        List<List<String>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(runId));
            row.add(s(run.get("execMode")));
            row.add(s(r.get("provider")));
            row.add(s(r.get("modelCode")));
            row.add(s(r.get("caseId")));
            row.add(s(r.get("attempt")));
            row.add(questionMap.getOrDefault(Long.parseLong(String.valueOf(r.get("caseId"))), ""));
            row.add(s(r.get("category")));
            row.add(s(r.get("expectedIntent")));
            row.add(s(r.get("expectedDocId")));
            row.add(s(r.get("actualIntent")));
            row.add(s(r.get("ragHit")));
            row.add(s(r.get("ragEmptyRecall")));
            row.add(s(r.get("actualDocIds")));
            row.add(s(r.get("refusal")));
            row.add(s(r.get("refusalRule")));
            row.add(s(r.get("hasCitation")));
            row.add(s(r.get("leakSuspected")));
            row.add(s(r.get("passed")));
            row.add(s(r.get("verdictRule")));
            row.add(s(r.get("failReason")));
            row.add(s(r.get("error")));
            row.add(s(r.get("latencyMs")));
            row.add(s(r.get("routeLatencyMs")));
            row.add(s(r.get("ragLatencyMs")));
            row.add(s(r.get("answerLatencyMs")));
            row.add(s(r.get("totalTokens")));
            row.add(s(r.get("tokenSource")));
            row.add(s(r.get("status")));
            data.add(row);
        }
        String content = csv.export(header, data);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=eval-run-" + runId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOf(Object o) {
        if (o == null) {
            return null;
        }
        return Integer.valueOf(String.valueOf(o));
    }
}
