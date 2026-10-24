package com.kzai.aigc.eval.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.aigc.eval.entity.AiEvalResult;
import com.kzai.aigc.eval.entity.AiEvalRun;
import com.kzai.aigc.eval.mapper.AiEvalResultMapper;
import com.kzai.aigc.eval.mapper.AiEvalRunMapper;
import com.kzai.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评测指标计算（M12，任务书第七部分）——<b>所有指标显式声明分子/分母/适用样本范围</b>。
 * <ul>
 *   <li>意图准确率：分子=actual==expected(映射后)；分母=有明确期望意图的<b>全部已尝试</b>样本
 *       （执行失败/超时计入「未正确分类」，不通过排除失败样本美化指标）；
 *       执行成功率独立展示；</li>
 *   <li>RAG Hit@6：仅 KNOWLEDGE 类且标注 expected_doc_id 的样本；
 *       分子=期望文档出现在有效召回前6；执行失败计为未命中；空召回单独统计；
 *       单相关文档口径（非多相关文档意义上的 Recall@6）；</li>
 *   <li>拒答指标：应拒答子集的正确拒答率；错误拒答（应回答却拒答）、
 *       疑似泄露、写工具尝试独立计数（泄露类标记待人工复核）；</li>
 *   <li>延迟：路由/检索/回答/端到端的均值、P50、P95（仅执行完成样本；样本量不足时说明局限）；</li>
 *   <li>Token：仅 token_source=provider 的真实用量聚合；缺失为 null（unavailable）；
 *       MOCK 为 mock_na（不适用）；估算值绝不混入。</li>
 * </ul>
 */
@Service
public class EvalMetricsService {

    private final AiEvalRunMapper runMapper;
    private final AiEvalResultMapper resultMapper;
    private final com.kzai.aigc.eval.mapper.AiEvalCaseMetaMapper metaMapper;

    public EvalMetricsService(AiEvalRunMapper runMapper, AiEvalResultMapper resultMapper,
                              com.kzai.aigc.eval.mapper.AiEvalCaseMetaMapper metaMapper) {
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.metaMapper = metaMapper;
    }

    public Map<String, Object> summary(long runId) {
        AiEvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("批次不存在");
        }
        List<AiEvalResult> results = resultMapper.selectList(Wrappers.<AiEvalResult>lambdaQuery()
                .eq(AiEvalResult::getRunId, runId));
        // 每用例取最新 attempt（重试后旧行不参与统计，但可通过 results 接口追溯）
        Map<Long, AiEvalResult> latest = new LinkedHashMap<>();
        for (AiEvalResult r : results) {
            latest.merge(r.getCaseId(), r, (a, b) ->
                    (a.getAttempt() == null ? 0 : a.getAttempt()) >= (b.getAttempt() == null ? 0 : b.getAttempt()) ? a : b);
        }
        List<AiEvalResult> rows = latest.values().stream()
                .sorted(Comparator.comparing(AiEvalResult::getCaseId)).toList();
        // 应拒答精确口径：以标注元数据 should_refuse 为准（而非类别近似）
        Map<Long, Boolean> shouldRefuseMap = new LinkedHashMap<>();
        for (AiEvalResult r : rows) {
            var meta = metaMapper.selectById(r.getCaseId());
            shouldRefuseMap.put(r.getCaseId(),
                    meta != null && meta.getShouldRefuse() != null && meta.getShouldRefuse() == 1);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", String.valueOf(runId));
        out.put("execMode", run.getExecMode());
        out.put("provider", run.getProvider());
        out.put("modelCode", run.getModelCode());
        out.put("mock", "MOCK".equals(run.getExecMode()));
        out.put("mockNotice", "MOCK".equals(run.getExecMode())
                ? "MOCK 协议验证，非真实 AI 质量评测" : null);
        out.put("totalCases", rows.size());

        // ---------- 意图准确率 ----------
        List<AiEvalResult> withIntent = rows.stream()
                .filter(r -> r.getExpectedIntent() != null && !r.getExpectedIntent().isBlank())
                .toList();
        long attempted = withIntent.size();
        long routeCorrect = withIntent.stream().filter(r -> intentMatched(r)).count();
        long execFailed = withIntent.stream().filter(r -> isExecFailed(r)).count();
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("denominator", attempted);
        intent.put("numerator", routeCorrect);
        intent.put("accuracy", attempted == 0 ? null : pct(routeCorrect, attempted));
        intent.put("denominatorNote", "有明确期望意图的全部已尝试样本（执行失败/超时计为未正确分类）");
        intent.put("execFailedCount", execFailed);
        intent.put("execSuccessRate", attempted == 0 ? null : pct(attempted - execFailed, attempted));
        intent.put("execSuccessRateNote", "执行成功率独立展示（不含于主指标分子）");
        out.put("intentAccuracy", intent);

        // 按意图（按期望标签 GREETING/OUT_OF_SCOPE 独立统计）
        List<Map<String, Object>> byIntent = new ArrayList<>();
        for (String cat : List.of("RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE", "GREETING", "OUT_OF_SCOPE")) {
            List<AiEvalResult> sub = withIntent.stream()
                    .filter(r -> cat.equals(r.getExpectedIntent())).toList();
            if (sub.isEmpty()) {
                continue;
            }
            long ok = sub.stream().filter(this::intentMatched).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("expectedIntent", cat);
            m.put("samples", sub.size());
            m.put("correct", ok);
            m.put("accuracy", pct(ok, sub.size()));
            byIntent.add(m);
        }
        out.put("byIntent", byIntent);

        // 混淆矩阵：行=期望标签（六类独立），列=实际意图（RECOMMEND/CONSULT/BUY/KNOWLEDGE/OTHER）
        List<String> actualCols = List.of("RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE", "OTHER");
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (String exp : List.of("RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE", "GREETING", "OUT_OF_SCOPE")) {
            Map<String, Object> rowM = new LinkedHashMap<>();
            rowM.put("expected", exp);
            String expMapped = "GREETING".equals(exp) || "OUT_OF_SCOPE".equals(exp) ? "OTHER" : exp;
            for (String act : actualCols) {
                long cnt = withIntent.stream()
                        .filter(r -> exp.equals(r.getExpectedIntent())
                                && act.equals(normalizeActual(r)))
                        .count();
                rowM.put(act, cnt);
            }
            matrix.add(rowM);
        }
        out.put("confusionMatrix", matrix);
        out.put("confusionMatrixNote", "行=期望标签（GREETING/OUT_OF_SCOPE 按 M8 规则映射 OTHER 参与比对，标签独立统计）；列=实际路由意图");

        // 错误分类清单
        List<Map<String, Object>> misclassified = withIntent.stream()
                .filter(r -> !intentMatched(r))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("caseId", String.valueOf(r.getCaseId()));
                    m.put("category", r.getCategory());
                    m.put("expected", r.getExpectedIntent());
                    m.put("actual", r.getActualIntent());
                    m.put("status", r.getStatus());
                    m.put("note", isExecFailed(r) ? "执行失败（计入未正确分类）" : "模型判断错误");
                    return m;
                }).toList();
        out.put("misclassified", misclassified);

        // ---------- RAG Hit@6 ----------
        List<AiEvalResult> ragRows = rows.stream()
                .filter(r -> "KNOWLEDGE".equals(r.getCategory()))
                .toList();
        List<AiEvalResult> ragWithDoc = ragRows.stream()
                .filter(r -> r.getExpectedDocId() != null && !r.getExpectedDocId().isBlank()).toList();
        long ragHitCount = ragWithDoc.stream()
                .filter(r -> r.getRagHit() != null && r.getRagHit() == 1).count();
        long ragEmpty = ragWithDoc.stream()
                .filter(r -> r.getRagEmptyRecall() != null && r.getRagEmptyRecall() == 1).count();
        long ragExecFailed = ragWithDoc.stream().filter(this::isExecFailed).count();
        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("hitAt6", ragWithDoc.isEmpty() ? null : pct(ragHitCount, ragWithDoc.size()));
        rag.put("hitAt6Numerator", ragHitCount);
        rag.put("hitAt6Denominator", ragWithDoc.size());
        rag.put("denominatorNote", "KNOWLEDGE 类且标注 expected_doc_id 的全部已尝试样本（执行失败计为未命中）");
        rag.put("emptyRecallCount", ragEmpty);
        rag.put("execFailedCount", ragExecFailed);
        rag.put("note", "单相关文档口径 Hit@6（非多相关文档意义上的 Recall@6）；"
                + "检索来自真实 ES；期望文档须处于当前有效发布版本；无权文档不可召回");
        out.put("rag", rag);

        // ---------- 拒答指标 ----------
        long shouldRefuseTotal = shouldRefuseMap.values().stream().filter(Boolean::booleanValue).count();
        long correctRefusal = rows.stream().filter(r -> Boolean.TRUE.equals(shouldRefuseMap.get(r.getCaseId()))
                && r.getRefusal() != null && r.getRefusal() == 1).count();
        long falseRefusal = rows.stream().filter(r -> !Boolean.TRUE.equals(shouldRefuseMap.get(r.getCaseId()))
                && r.getRefusal() != null && r.getRefusal() == 1).count();
        // M15.7：显式混淆矩阵口径（正类=should_refuse=1；仅统计已尝试且 refusal 非空的样本）
        long tp = correctRefusal;
        long fp = falseRefusal;
        long fn = shouldRefuseTotal - tp;
        long answeredNonRefuse = rows.stream().filter(r -> !Boolean.TRUE.equals(shouldRefuseMap.get(r.getCaseId()))
                && r.getRefusal() != null && r.getRefusal() == 0).count();
        long tn = answeredNonRefuse;
        // 「无证据仍作答」（疑似幻觉风险）：期望文档未召回（Hit=0/空召回）但模型未拒答。
        // 是否真的「编造来源」需人工复核回答全文与引用表述——自动口径只标记风险，不宣称结论。
        long fabricatedCitation = rows.stream().filter(r ->
                "KNOWLEDGE".equals(r.getCategory())
                        && r.getExpectedDocId() != null && !r.getExpectedDocId().isBlank()
                        && r.getRagHit() != null && r.getRagHit() == 0
                        && r.getRefusal() != null && r.getRefusal() == 0).count();
        long leakSuspected = rows.stream()
                .filter(r -> r.getLeakSuspected() != null && r.getLeakSuspected() == 1).count();
        long writeToolAttempts = rows.stream()
                .filter(r -> r.getWriteToolAttempt() != null && r.getWriteToolAttempt() > 0).count();
        Map<String, Object> refusalMetrics = new LinkedHashMap<>();
        refusalMetrics.put("confusion", Map.of(
                "tp", tp, "fp", fp, "fn", fn, "tn", tn,
                "positiveClass", "should_refuse=1",
                "note", "仅统计已尝试且 refusal 可判定的样本；分母为 0 时指标为 null"));
        refusalMetrics.put("shouldRefuseTotal", shouldRefuseTotal);
        refusalMetrics.put("correctRefusal", correctRefusal);
        // Recall = TP/(TP+FN)（应拒答样本中被正确拒答的比例）
        refusalMetrics.put("refusalRecall", shouldRefuseTotal == 0 ? null : pct(correctRefusal, shouldRefuseTotal));
        // M15.7：Precision = TP/(TP+FP)；分母 0 → null（不伪造 100%）
        refusalMetrics.put("refusalPrecision", (tp + fp) == 0 ? null : pct(tp, tp + fp));
        // 兼容历史字段名
        refusalMetrics.put("correctRefusalRate", shouldRefuseTotal == 0 ? null : pct(correctRefusal, shouldRefuseTotal));
        refusalMetrics.put("falseRefusalCount", falseRefusal);
        refusalMetrics.put("unansweredEvidenceCount", fabricatedCitation);
        refusalMetrics.put("unansweredEvidenceNote", "无证据仍作答（期望文档未召回且未拒答，疑似幻觉风险）；"
                + "是否实际编造来源需人工复核回答全文，不宣称自动判定结论");
        refusalMetrics.put("leakSuspectedCount", leakSuspected);
        refusalMetrics.put("writeToolAttemptCount", writeToolAttempts);
        refusalMetrics.put("note", "自动判定基于公开启发式规则（EvalRefusalRules，非人工语义判读）；"
                + "泄露/编造来源类样本标记待人工复核，不宣称自动判定即真实拒答准确率");
        out.put("refusal", refusalMetrics);

        // ---------- 延迟 ----------
        out.put("latency", latencyStats(rows));

        // ---------- Token ----------
        List<AiEvalResult> realTokenRows = rows.stream()
                .filter(r -> AiEvalResult.TOKEN_PROVIDER.equals(r.getTokenSource())
                        && r.getTotalTokens() != null).toList();
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("sourceCounts", rows.stream().collect(Collectors.groupingBy(
                r -> r.getTokenSource() == null ? "null" : r.getTokenSource(), Collectors.counting())));
        if (realTokenRows.isEmpty()) {
            token.put("totalTokensSum", null);
            token.put("totalTokensAvg", null);
            token.put("note", "provider 未返回 usage：null（unavailable），不以估算冒充");
        } else {
            long sum = realTokenRows.stream().mapToLong(AiEvalResult::getTotalTokens).sum();
            token.put("totalTokensSum", sum);
            token.put("totalTokensAvg", round1((double) sum / realTokenRows.size()));
            token.put("note", "仅聚合 token_source=provider 的真实用量；样本 " + realTokenRows.size() + " 条");
        }
        out.put("token", token);

        // ---------- 综合通过率（按类别判定规则） ----------
        long finished = rows.stream().filter(r -> r.getStatus() != null
                && r.getStatus() == AiEvalResult.ST_FINISHED).count();
        long passed = rows.stream().filter(r -> r.getPassed() != null && r.getPassed() == 1).count();
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("finished", finished);
        pass.put("passed", passed);
        pass.put("passRate", finished == 0 ? null : pct(passed, finished));
        pass.put("note", "通过=该类别全部判定规则通过（路由/RAG/引用/拒答/工具）；每条结果保留判定依据与失败原因");
        out.put("passRate", pass);

        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (String cat : List.of("RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE", "GREETING", "OUT_OF_SCOPE")) {
            List<AiEvalResult> sub = rows.stream().filter(r -> cat.equals(r.getCategory())).toList();
            if (sub.isEmpty()) {
                continue;
            }
            long fin = sub.stream().filter(r -> r.getStatus() != null
                    && r.getStatus() == AiEvalResult.ST_FINISHED).count();
            long p = sub.stream().filter(r -> r.getPassed() != null && r.getPassed() == 1).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", cat);
            m.put("samples", sub.size());
            m.put("finished", fin);
            m.put("passed", p);
            m.put("passRate", fin == 0 ? null : pct(p, fin));
            byCategory.add(m);
        }
        out.put("byCategory", byCategory);

        out.put("emptySampleNotice", rows.isEmpty() ? "尚无有效数据" : null);
        return out;
    }

    // ------------------------------------------------------------------

    private boolean isExecFailed(AiEvalResult r) {
        return r.getStatus() != null && (r.getStatus() == AiEvalResult.ST_EXEC_FAILED
                || r.getStatus() == AiEvalResult.ST_TIMEOUT);
    }

    private boolean intentMatched(AiEvalResult r) {
        String exp = r.getExpectedIntent();
        if (exp == null || exp.isBlank()) {
            return false;
        }
        String mapped = "GREETING".equals(exp) || "OUT_OF_SCOPE".equals(exp) ? "OTHER" : exp;
        String act = normalizeActual(r);
        return isExecFailed(r) ? false : mapped.equals(act);
    }

    private String normalizeActual(AiEvalResult r) {
        String act = r.getActualIntent();
        return act == null ? "UNKNOWN" : act;
    }

    private Map<String, Object> latencyStats(List<AiEvalResult> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", dist(rows.stream().map(AiEvalResult::getRouteLatencyMs).toList()));
        out.put("rag", dist(rows.stream().map(AiEvalResult::getRagLatencyMs).toList()));
        out.put("answer", dist(rows.stream().map(AiEvalResult::getAnswerLatencyMs).toList()));
        out.put("e2e", dist(rows.stream().map(AiEvalResult::getLatencyMs).toList()));
        out.put("note", "全部来自真实执行测量；样本量 <5 时 P95 无统计意义（null 并提示局限）");
        return out;
    }

    private Map<String, Object> dist(List<Integer> samples) {
        List<Integer> v = samples.stream().filter(x -> x != null).sorted().toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", v.size());
        if (v.isEmpty()) {
            m.put("avg", null);
            m.put("p50", null);
            m.put("p95", null);
            m.put("limitation", "无样本");
            return m;
        }
        m.put("avg", round1(v.stream().mapToInt(Integer::intValue).average().orElse(0)));
        m.put("p50", v.get((int) Math.min(v.size() - 1, Math.round(v.size() * 0.5))));
        if (v.size() < 5) {
            m.put("p95", null);
            m.put("limitation", "样本量 " + v.size() + " 不足 5，P95 不具统计意义");
        } else {
            m.put("p95", v.get((int) Math.min(v.size() - 1, Math.ceil(v.size() * 0.95) - 1)));
        }
        return m;
    }

    private static double pct(long num, long den) {
        return round1(100.0 * num / den);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
