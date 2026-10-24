package com.kzai.aigc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.agent.AgentTypeEnum;
import com.kzai.aigc.prompt.AgentPromptSpec;
import com.kzai.aigc.prompt.PromptAudit;
import com.kzai.aigc.prompt.PromptManager;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Prompt 模板管理（M8，管理员专用；方案为「内置默认 + Nacos 直发」，无持久化表）。
 * <p>
 * 能力：查看五类生效模板、单类预览校验、整体发布到 Nacos（写后回读一致性校验，
 * 失败不得假装成功，成功即热生效并写 Redis 缓存供重启恢复）。
 * 草稿/历史版本/回滚需要持久化表——按用户决策<b>未启用</b>（见 09-M8执行报告）。
 * 权限：后端 @RequireRoles(ADMIN) 强制，不依赖前端隐藏。
 */
@RestController
@RequestMapping("/admin/ai/prompts")
@RequireRoles({Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class PromptAdminController {

    private final PromptManager promptManager;
    private final PromptAudit promptAudit;
    private final ObjectMapper objectMapper;
    private final com.kzai.aigc.supervision.OperationAuditBridge supervisionAudit; // M13 审计落库
    private final com.kzai.aigc.prompt.PromptVersionService promptVersionService; // M15.19-D

    /** 五类生效模板 + Nacos 状态 */
    @GetMapping
    public R<Map<String, Object>> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Map.Entry<AgentTypeEnum, AgentPromptSpec> e : promptManager.effective().entrySet()) {
            agents.add(toMap(e.getKey(), e.getValue()));
        }
        data.put("agents", agents);
        data.put("nacos", promptManager.nacosStatus());
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 单类生效模板 */
    @GetMapping("/{agentType}")
    public R<Map<String, Object>> detail(@PathVariable("agentType") String agentType) {
        AgentTypeEnum type = parseType(agentType);
        promptAudit.recordView(UserContext.getUserId(), type.name());
        return R.ok(toMap(type, promptManager.get(type))).requestId(UserContext.getRequestId());
    }

    /**
     * 预览校验（无副作用，不发布、不执行任何工具）：
     * 校验单类模板片段的长度/占位符/枚举约束，回显渲染结果与错误列表。
     */
    @PostMapping("/{agentType}/preview")
    public R<Map<String, Object>> preview(@PathVariable("agentType") String agentType,
                                          @RequestBody Map<String, Object> body) {
        AgentTypeEnum type = parseType(agentType);
        String systemPrompt = strOf(body, "systemPrompt");
        String welcomeText = strOf(body, "welcomeText");
        List<String> hotQuestions = hotOf(body);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            errors.add("systemPrompt 不能为空");
        } else {
            if (systemPrompt.length() > 8000) {
                errors.add("systemPrompt 超长（>8000）");
            }
            if (systemPrompt.contains("${") || systemPrompt.toLowerCase().contains("apikey")
                    || systemPrompt.toLowerCase().contains("api_key")) {
                errors.add("systemPrompt 含非法占位符/密钥字样（防配置注入）");
            }
            if (type == AgentTypeEnum.ROUTE) {
                for (String keyword : new String[]{"RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE", "OTHER"}) {
                    if (!systemPrompt.contains(keyword)) {
                        errors.add("ROUTE.systemPrompt 必须包含意图枚举 " + keyword);
                    }
                }
            }
        }
        if (welcomeText != null && welcomeText.length() > 500) {
            errors.add("welcomeText 超长（>500）");
        }
        if (hotQuestions != null && hotQuestions.size() > 8) {
            errors.add("hotQuestions 超过 8 条");
        }
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        if (errors.isEmpty()) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("systemPrompt", systemPrompt);
            rendered.put("welcomeText", welcomeText == null ? "" : welcomeText);
            rendered.put("hotQuestions", hotQuestions == null ? List.of() : hotQuestions);
            result.put("rendered", rendered);
        }
        return R.ok(result).requestId(UserContext.getRequestId());
    }

    /**
     * 发布到 Nacos（直发方案）：将编辑的单类模板与其余四类当前生效内容合成完整配置文档，
     * 经 PromptManager 校验 → Nacos 写入 → 回读一致性校验 → 热生效 + Redis 缓存。
     * 任何一步失败返回 published=false 与错误（不假装成功）。
     */
    @PostMapping("/{agentType}/publish")
    public R<Map<String, Object>> publish(@PathVariable("agentType") String agentType,
                                          @RequestBody Map<String, Object> body) {
        AgentTypeEnum type = parseType(agentType);
        Long operator = requireAdminId();
        String systemPrompt = strOf(body, "systemPrompt");
        String welcomeText = strOf(body, "welcomeText");
        List<String> hotQuestions = hotOf(body);

        // 构造完整文档：被编辑的 Agent 用新内容，其余用当前生效内容
        Map<String, Object> prompts = new LinkedHashMap<>();
        for (Map.Entry<AgentTypeEnum, AgentPromptSpec> e : promptManager.effective().entrySet()) {
            AgentPromptSpec spec = e.getValue();
            boolean edited = e.getKey() == type;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("systemPrompt", edited ? systemPrompt : spec.getSystemPrompt());
            p.put("welcomeText", edited ? (welcomeText == null ? "" : welcomeText) : spec.getWelcomeText());
            p.put("hotQuestions", edited ? (hotQuestions == null ? List.of() : hotQuestions)
                    : spec.getHotQuestions());
            prompts.put(e.getKey().name(), p);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", "pub-" + java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        doc.put("prompts", prompts);

        String content;
        try {
            content = objectMapper.writeValueAsString(doc);
        } catch (Exception ex) {
            throw new BizException("发布内容序列化失败");
        }
        String error = promptManager.applyFromPublish(content, String.valueOf(operator));
        String version = error == null
                ? promptManager.get(type).getVersion() : null;
        promptAudit.recordPublish(operator, type.name(), version, error == null, error);
        // M15.19-D：发布记录持久化（失败也记录——保留最后有效版本可回滚）
        // M15.20-A：版本记录改为单模板粒度（content=被编辑类的 JSON）——回滚只恢复选定模板
        String singleTemplateJson;
        try {
            singleTemplateJson = objectMapper.writeValueAsString(prompts.get(type.name()));
        } catch (Exception ex) {
            singleTemplateJson = "{}";
        }
        promptVersionService.record(type.name(), singleTemplateJson,
                "PUBLISH", operator, error == null, error == null ? "发布成功" : error);
        // M13：Prompt 发布审计落库（成功/失败均记录；不含 Prompt 正文）
        supervisionAudit.write(operator, String.valueOf(operator), "PROMPT", "PROMPT_PUBLISH",
                "ai_prompt", null,
                "{\"agentType\":\"" + type.name() + "\",\"version\":\"" + version
                        + "\",\"success\":" + (error == null) + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("published", error == null);
        data.put("agentType", type.name());
        data.put("version", version);
        if (error != null) {
            data.put("error", error);
        }
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** M15.19-D：保存草稿（DRAFT 记录；不影响当前生效版本） */
    @PostMapping("/{agentType}/save-draft")
    public R<Map<String, Object>> saveDraft(@PathVariable("agentType") String agentType,
                                            @RequestBody Map<String, Object> body) {
        AgentTypeEnum type = parseType(agentType);
        Long operator = requireAdminId();
        Map<String, Object> prompts = new LinkedHashMap<>();
        prompts.put(type.name(), new LinkedHashMap<>(Map.of(
                "systemPrompt", strOf(body, "systemPrompt"),
                "welcomeText", strOf(body, "welcomeText"),
                "hotQuestions", hotOf(body))));
        String content;
        try {
            content = objectMapper.writeValueAsString(prompts);
        } catch (Exception ex) {
            throw new BizException("草稿序列化失败");
        }
        var v = promptVersionService.record(type.name(), content, "DRAFT", operator, true, "草稿");
        return R.ok(Map.of("draftVersionNo", v.entity().getVersionNo()));
    }

    /** 发布审计摘要（管理员可见） */
    /** M15.19-D：版本历史（草稿/发布/回滚记录倒序；含内容供回滚预览） */
    @GetMapping("/{agentType}/history")
    public R<List<Map<String, Object>>> history(@PathVariable("agentType") String agentType) {
        AgentTypeEnum type = parseType(agentType);
        requireAdminId();
        return R.ok(promptVersionService.history(type.name()).stream()
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionNo", v.getVersionNo());
                    m.put("action", v.getAction());
                    m.put("operator", v.getOperator());
                    m.put("resultOk", v.getResultOk() != null && v.getResultOk() == 1);
                    m.put("resultMsg", v.getResultMsg());
                    m.put("createTime", String.valueOf(v.getCreateTime()));
                    m.put("content", v.getContent());
                    return m;
                }).toList());
    }

    /**
     * M15.20-A：回滚（单模板粒度）——选定模板恢复到历史内容，其余四类保持当前生效内容，
     * 合成完整配置发布；产生新发布记录（来源版本与本次版本分离），历史不覆盖。
     */
    @PostMapping("/{agentType}/rollback/{versionNo}")
    public R<Map<String, Object>> rollback(@PathVariable("agentType") String agentType,
                                           @PathVariable("versionNo") int versionNo) {
        AgentTypeEnum type = parseType(agentType);
        Long operator = requireAdminId();
        var target = promptVersionService.byVersionNo(type.name(), versionNo);
        if (target == null || target.getResultOk() == null || target.getResultOk() != 1
                || "DRAFT".equals(target.getAction())) {
            throw new BizException("目标版本不存在或非成功发布版本");
        }
        // 解析历史单模板内容
        Map<String, Object> targetSpec;
        try {
            targetSpec = objectMapper.readValue(target.getContent(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception ex) {
            throw new BizException("历史版本内容解析失败");
        }
        // 合成：该类用历史内容，其余类保持当前生效内容
        Map<String, Object> prompts = new LinkedHashMap<>();
        for (Map.Entry<AgentTypeEnum, AgentPromptSpec> e : promptManager.effective().entrySet()) {
            boolean edited = e.getKey() == type;
            Map<String, Object> p = new LinkedHashMap<>();
            if (edited) {
                p.put("systemPrompt", targetSpec.get("systemPrompt"));
                p.put("welcomeText", targetSpec.get("welcomeText"));
                p.put("hotQuestions", targetSpec.get("hotQuestions") == null
                        ? List.of() : targetSpec.get("hotQuestions"));
            } else {
                p.put("systemPrompt", e.getValue().getSystemPrompt());
                p.put("welcomeText", e.getValue().getWelcomeText());
                p.put("hotQuestions", e.getValue().getHotQuestions());
            }
            prompts.put(e.getKey().name(), p);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", "pub-" + java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        doc.put("prompts", prompts);
        String content;
        try {
            content = objectMapper.writeValueAsString(doc);
        } catch (Exception ex) {
            throw new BizException("回滚内容序列化失败");
        }
        String error = promptManager.applyFromPublish(content, String.valueOf(operator));
        String version = error == null ? promptManager.get(type).getVersion() : null;
        // 记录本次回滚发布（content=单模板历史内容；resultMsg 区分来源版本与本次版本）
        promptVersionService.record(type.name(), target.getContent(),
                "ROLLBACK", operator, error == null,
                error == null ? "回滚自版本 " + versionNo + "，本次新版本 " + version
                        : error + "（最后有效版本已保留）");
        promptAudit.recordPublish(operator, type.name(), version, error == null, error);
        if (error != null) {
            throw new BizException("回滚失败：" + error + "（最后有效版本已保留）");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rolledBack", true);
        data.put("agentType", type.name());
        data.put("fromVersionNo", versionNo);
        data.put("version", version == null ? "" : version);
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** M15.20-A：载入最新草稿（恢复编辑入口；返回该类型草稿内容） */
    @GetMapping("/{agentType}/load-draft")
    public R<Map<String, Object>> loadDraft(@PathVariable("agentType") String agentType) {
        parseType(agentType);
        requireAdminId();
        var draft = promptVersionService.history(agentType).stream()
                .filter(v -> "DRAFT".equals(v.getAction()))
                .findFirst().orElse(null);
        if (draft == null) {
            return R.ok(Map.of("exists", false));
        }
        Map<String, Object> content;
        try {
            content = objectMapper.readValue(draft.getContent(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception ex) {
            throw new BizException("草稿内容解析失败");
        }
        return R.ok(Map.of("exists", true, "versionNo", draft.getVersionNo(),
                "createTime", String.valueOf(draft.getCreateTime()),
                "systemPrompt", content.getOrDefault("systemPrompt", ""),
                "welcomeText", content.getOrDefault("welcomeText", ""),
                "hotQuestions", content.getOrDefault("hotQuestions", List.of())));
    }


    @GetMapping("/audit")
    public R<Map<String, Object>> audit() {
        return R.ok(promptAudit.snapshot()).requestId(UserContext.getRequestId());
    }

    // ------------------------------------------------------------------

    private static Map<String, Object> toMap(AgentTypeEnum type, AgentPromptSpec spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentType", type.name());
        m.put("displayName", type.getDisplayName());
        m.put("systemPrompt", spec.getSystemPrompt());
        m.put("welcomeText", spec.getWelcomeText());
        m.put("hotQuestions", spec.getHotQuestions());
        m.put("source", spec.getSource());
        m.put("version", spec.getVersion());
        m.put("hasTools", type.hasTools());
        return m;
    }

    private static AgentTypeEnum parseType(String agentType) {
        try {
            return AgentTypeEnum.valueOf(agentType.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("未知 Agent 类型: " + agentType);
        }
    }

    private static Long requireAdminId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new com.kzai.common.exception.AuthException(
                    com.kzai.common.result.ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    @SuppressWarnings("unchecked")
    private static List<String> hotOf(Map<String, Object> body) {
        Object v = body == null ? null : body.get("hotQuestions");
        return v instanceof List ? (List<String>) v : null;
    }

    private static String strOf(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
