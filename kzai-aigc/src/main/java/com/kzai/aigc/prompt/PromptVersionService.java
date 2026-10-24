package com.kzai.aigc.prompt;

import com.kzai.aigc.agent.AgentTypeEnum;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * M15.20-A：Prompt 版本记录（单模板粒度——回滚只恢复选定模板，不影响其他模板后续更新）。
 * version_no 唯一约束（uk_agent_version）保证并发安全：MAX+1 冲突时重试；
 * content 语义=单模板 JSON {systemPrompt, welcomeText, hotQuestions}（历史完整五类文档已迁移拆分）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptVersionService {

    private final PromptVersionMapper mapper;

    public record RecordResult(PromptVersionEntity entity, boolean retried) {
    }

    /** 记录版本（MAX+1+唯一索引冲突重试，并发安全；重复记录迁移前已核查=0） */
    @Transactional
    public RecordResult record(String agentType, String content, String action,
                               Long operator, boolean ok, String msg) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Integer max = mapper.selectList(Wrappers.<PromptVersionEntity>lambdaQuery()
                            .eq(PromptVersionEntity::getAgentType, agentType)
                            .orderByDesc(PromptVersionEntity::getVersionNo).last("LIMIT 1"))
                    .stream().findFirst().map(PromptVersionEntity::getVersionNo).orElse(0);
            PromptVersionEntity e = new PromptVersionEntity();
            e.setAgentType(agentType);
            e.setContent(content);
            e.setVersionNo((max == null ? 0 : max) + 1);
            e.setAction(action);
            e.setOperator(operator);
            e.setResultOk(ok ? 1 : 0);
            e.setResultMsg(msg);
            e.setCreateTime(LocalDateTime.now());
            try {
                mapper.insert(e);
                return new RecordResult(e, attempt > 0);
            } catch (DuplicateKeyException dup) {
                // 并发 MAX+1 冲突：重试（唯一索引保证最终唯一）
            }
        }
        throw new BizException("版本号生成冲突，请稍后重试");
    }

    /** 历史列表（倒序） */
    public List<PromptVersionEntity> history(String agentType) {
        return mapper.selectList(Wrappers.<PromptVersionEntity>lambdaQuery()
                .eq(PromptVersionEntity::getAgentType, agentType)
                .orderByDesc(PromptVersionEntity::getVersionNo));
    }

    /** 按版本号取（回滚目标/会话绑定） */
    public PromptVersionEntity byVersionNo(String agentType, int versionNo) {
        return mapper.selectList(Wrappers.<PromptVersionEntity>lambdaQuery()
                        .eq(PromptVersionEntity::getAgentType, agentType)
                        .eq(PromptVersionEntity::getVersionNo, versionNo))
                .stream().findFirst().orElse(null);
    }

    /** 指定类型的最新成功发布版本（会话创建时绑定） */
    public PromptVersionEntity latestOkPublish(String agentType) {
        return mapper.selectList(Wrappers.<PromptVersionEntity>lambdaQuery()
                        .eq(PromptVersionEntity::getAgentType, agentType)
                        .eq(PromptVersionEntity::getResultOk, 1)
                        .in(PromptVersionEntity::getAction, "PUBLISH", "ROLLBACK")
                        .orderByDesc(PromptVersionEntity::getVersionNo).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }


    /**
     * M15.20-A：当前各类型最新成功发布版本号 JSON（会话创建时快照绑定）。
     * 无发布记录的类型不写入（该类型会话回退当前生效模板）。
     */
    public String currentVersionMapJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (PromptVersionEntity e : mapper.selectList(Wrappers.<PromptVersionEntity>lambdaQuery()
                .eq(PromptVersionEntity::getResultOk, 1)
                .in(PromptVersionEntity::getAction, "PUBLISH", "ROLLBACK")
                .orderByDesc(PromptVersionEntity::getVersionNo))) {
            map.putIfAbsent(e.getAgentType(), e.getVersionNo());
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception ex) {
            return "{}";
        }
    }


    /** 版本内容本地缓存（版本内容不可变；容量有界） */
    private final java.util.Map<String, AgentPromptSpec> specCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * M15.20-A：从会话版本映射解析指定类型的 Prompt 快照。
     * 映射无该类型/版本记录缺失 → null（调用方回退当前生效）。
     */
    public AgentPromptSpec specFromVersionMap(String versionMapJson, AgentTypeEnum agentType) {
        String cacheKey = agentType.name() + ":" + versionMapJson.hashCode();
        AgentPromptSpec cached = specCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Integer> map = om.readValue(versionMapJson,
                    om.getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, Integer.class));
            Integer vno = map.get(agentType.name());
            if (vno == null) {
                return null;
            }
            var entity = byVersionNo(agentType.name(), vno);
            if (entity == null) {
                return null;
            }
            Map<String, Object> content = om.readValue(entity.getContent(),
                    om.getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, Object.class));
            @SuppressWarnings("unchecked")
            List<String> hot = content.get("hotQuestions") instanceof List
                    ? (List<String>) content.get("hotQuestions") : List.of();
            AgentPromptSpec spec = AgentPromptSpec.builder()
                    .agentType(agentType)
                    .systemPrompt(String.valueOf(content.getOrDefault("systemPrompt", "")))
                    .welcomeText(String.valueOf(content.getOrDefault("welcomeText", "")))
                    .hotQuestions(hot)
                    .source("VERSION_SNAPSHOT")
                    .version(String.valueOf(vno))
                    .build();
            specCache.put(cacheKey, spec);
            return spec;
        } catch (Exception e) {
            log.warn("[prompt] 版本映射解析失败（回退当前生效）：{}", e.getMessage());
            return null;
        }
    }


    /**
     * M15.21：会话创建快照——五类全部绑定：有发布记录→最新版本号；无记录→记录 INITIAL 版本
     * （content=当前生效单模板内容），避免该类型后续首次发布导致历史会话漂移。
     *
     * @return type→versionNo JSON（全部五类有值）
     */
    public String snapshotAllTypes(java.util.function.Function<AgentTypeEnum, AgentPromptSpec> currentSpecOf) {
        Map<String, Object> map = new LinkedHashMap<>();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (AgentTypeEnum type : AgentTypeEnum.values()) {
            PromptVersionEntity latest = latestOkPublish(type.name());
            if (latest != null) {
                map.put(type.name(), latest.getVersionNo());
            } else {
                // 无发布记录：当前生效内容固化为 INITIAL 版本
                AgentPromptSpec spec = currentSpecOf.apply(type);
                String content;
                try {
                    content = om.writeValueAsString(Map.of(
                            "systemPrompt", spec.getSystemPrompt() == null ? "" : spec.getSystemPrompt(),
                            "welcomeText", spec.getWelcomeText() == null ? "" : spec.getWelcomeText(),
                            "hotQuestions", spec.getHotQuestions() == null ? List.of() : spec.getHotQuestions()));
                } catch (Exception ex) {
                    content = "{}";
                }
                var v = record(type.name(), content, "INITIAL", 0L, true, "会话快照基线（当前生效内容固化）");
                map.put(type.name(), v.entity().getVersionNo());
            }
        }
        try {
            return om.writeValueAsString(map);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
