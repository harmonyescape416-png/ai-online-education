package com.kzai.aigc.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.entity.SysConfigParam;
import com.kzai.aigc.config.entity.SysConfigPublish;
import com.kzai.aigc.config.mapper.SysConfigParamMapper;
import com.kzai.aigc.config.mapper.SysConfigPublishMapper;
import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 系统参数管理（草稿 → 发布 → 热更新 → 记录，用户批准方案）：
 * <ul>
 *   <li>草稿与生效分离：保存草稿不影响运行（DynamicAiParams 不变）；</li>
 *   <li>范围校验：value_type + min/max（保存草稿与发布双重校验）；</li>
 *   <li>乐观锁：草稿保存/发布按 version 条件更新，防旧数据覆盖新配置；</li>
 *   <li>发布链：Nacos 写入 → 有界回读一致性校验（≤5s，容忍 Derby 延迟，与 M8 同模式）
 *       → 进程内原子热生效 + Redis 恢复 → runtime_verified 置位；
 *       任何一步失败：保留最后一次有效配置 + 记录 ST_FAILED + error；</li>
 *   <li>Nacos 不可用：明确报错，不静默；</li>
 *   <li>发布历史只插入；本表不承载 Prompt 版本管理（D8-7 边界）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigParamService {

    private final SysConfigParamMapper paramMapper;
    private final SysConfigPublishMapper publishMapper;
    private final DynamicAiParams dynamic;
    private final ObjectMapper om;
    private final StringRedisTemplate redis;
    private final com.kzai.aigc.supervision.OperationAuditBridge audit;

    @Value("${spring.cloud.nacos.discovery.server-addr}")
    private String nacosAddr;

    /** 启动初始化：表空时插入 5 个默认参数（幂等） */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void initDefaults() {
        if (paramMapper.selectCount(null) > 0) {
            return;
        }
        insertParam(DynamicAiParams.KEY_TOP_K, "检索 TopK", "知识库检索单次返回切片数", "6", "INT", "1", "20");
        insertParam(DynamicAiParams.KEY_MIN_SCORE, "相似度阈值", "cosine 归一化相似度阈值（ES _score 直接可比）", "0.6", "DOUBLE", "0", "1");
        insertParam(DynamicAiParams.KEY_HISTORY_LIMIT, "会话历史上限", "会话列表最大返回条数", "30", "INT", "5", "50");
        insertParam(DynamicAiParams.KEY_TIMEOUT_SECONDS, "模型请求超时", "单次模型调用超时秒数", "60", "INT", "1", "300");
        insertParam(DynamicAiParams.KEY_DAILY_LIMIT, "单用户每日 AI 调用上限", "按自然日累计的每用户调用次数上限", "200", "INT", "1", "1000");
        // M14 语音参数（数据行扩展，合法流程；幂等按 key）
        insertParam(DynamicAiParams.KEY_VOICE_ENABLED, "语音功能开关", "总开关：关闭后 STT/TTS 全部不可用", "true", "BOOL", "false", "true");
        insertParam(DynamicAiParams.KEY_STT_MAX_BYTES, "语音上传大小上限", "STT 音频文件最大字节数", "5242880", "INT", "65536", "20971520");
        insertParam(DynamicAiParams.KEY_STT_MAX_SECONDS, "语音录音时长上限", "STT 单次录音最大秒数", "60", "INT", "5", "120");
        insertParam(DynamicAiParams.KEY_TTS_MAX_CHARS, "播报文本长度上限", "TTS 单次合成最大字符数", "500", "INT", "50", "2000");
        insertParam(DynamicAiParams.KEY_VOICE_CONCURRENCY, "语音并发上限", "语音服务全局并发请求数", "2", "INT", "1", "8");
        log.info("[config.params] 默认参数初始化完成");
    }

    private void insertParam(String key, String name, String desc, String value, String type, String min, String max) {
        if (paramMapper.selectCount(Wrappers.<SysConfigParam>lambdaQuery()
                .eq(SysConfigParam::getParamKey, key)) > 0) {
            return;
        }
        SysConfigParam p = new SysConfigParam();
        p.setParamKey(key);
        p.setParamName(name);
        p.setDescription(desc);
        p.setEffectiveValue(value);
        p.setValueType(type);
        p.setMinValue(min);
        p.setMaxValue(max);
        p.setVersion(0);
        paramMapper.insert(p);
    }

    /** 参数列表（附当前运行时生效值——DynamicAiParams 快照，可能是热更新后的值） */
    public List<Map<String, Object>> list() {
        Map<String, String> runtime = dynamic.snapshot();
        return paramMapper.selectList(Wrappers.<SysConfigParam>lambdaQuery()
                        .orderByAsc(SysConfigParam::getId)).stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(p.getId()));
                    m.put("paramKey", p.getParamKey());
                    m.put("paramName", p.getParamName());
                    m.put("description", p.getDescription());
                    m.put("effectiveValue", p.getEffectiveValue());
                    m.put("draftValue", p.getDraftValue());
                    m.put("runtimeValue", runtime.get(p.getParamKey()));
                    m.put("valueType", p.getValueType());
                    m.put("minValue", p.getMinValue());
                    m.put("maxValue", p.getMaxValue());
                    m.put("version", p.getVersion());
                    m.put("draftUpdateTime", p.getDraftUpdateTime());
                    m.put("publishTime", p.getPublishTime());
                    return m;
                }).toList();
    }

    /** 保存草稿（范围校验 + version 乐观锁；不影响运行） */
    public void saveDraft(Long paramId, String draftValue, Integer expectedVersion,
                          long adminId, String adminName) {
        SysConfigParam p = requireParam(paramId);
        validateValue(p, draftValue);
        checkVersion(p, expectedVersion);
        SysConfigParam upd = new SysConfigParam();
        upd.setDraftValue(draftValue);
        upd.setDraftUpdatedBy(adminId);
        upd.setDraftUpdateTime(LocalDateTime.now());
        upd.setVersion(p.getVersion() + 1); // 每次草稿变更递增版本（乐观锁核心）
        int n = paramMapper.update(upd, Wrappers.<SysConfigParam>lambdaUpdate()
                .eq(SysConfigParam::getId, paramId)
                .eq(SysConfigParam::getVersion, p.getVersion()));
        if (n != 1) {
            throw new BizException("配置已被他人修改（版本冲突），请刷新后重试");
        }
        audit.write(adminId, adminName, "CONFIG", "PARAM_DRAFT_SAVE",
                "SYS_CONFIG", paramId,
                "{\"key\":\"" + p.getParamKey() + "\",\"draft\":\"" + draftValue + "\"}");
    }

    /**
     * 发布：全部有草稿的参数一起发布（原子；任一非法则整体拒绝）。
     * 链路：范围校验 → version 条件更新生效值 → Nacos 写入 → 有界回读 → 热生效 + Redis
     * → runtime_verified；失败回退 values_before。
     */
    public Map<String, Object> publish(long adminId, String adminName) {
        List<SysConfigParam> all = paramMapper.selectList(null);
        List<SysConfigParam> toPublish = all.stream().filter(p -> p.getDraftValue() != null).toList();
        if (toPublish.isEmpty()) {
            throw new BizException("没有待发布的参数草稿");
        }
        // 全量范围校验（先校验后执行，任一非法整体拒绝）
        for (SysConfigParam p : toPublish) {
            validateValue(p, p.getDraftValue());
        }

        Map<String, String> before = dynamic.snapshot();
        Map<String, String> target = new LinkedHashMap<>(before);
        for (SysConfigParam p : toPublish) {
            target.put(p.getParamKey(), p.getDraftValue());
        }

        SysConfigPublish record = new SysConfigPublish();
        record.setPublishVersion(nextPublishVersion());
        record.setParamKeys(toJson(toPublish.stream().map(SysConfigParam::getParamKey).toList()));
        record.setConfigSnapshot(toJson(target));
        record.setValuesBefore(toJson(before));
        record.setValuesAfter(toJson(target));
        record.setPublishStatus(SysConfigPublish.ST_PUBLISHING);
        record.setRuntimeVerified(SysConfigPublish.RT_UNVERIFIED);
        record.setNacosDataId(DynamicAiParams.DATA_ID);
        record.setPublishedBy(adminId);
        record.setPublishedByName(adminName);
        publishMapper.insert(record);

        // ① Nacos 可用性前置检查
        if (!dynamic.nacosAvailable()) {
            failPublish(record, "Nacos 不可用，发布中止（正式配置未变更）");
            throw new BizException("Nacos 不可用，发布失败；最后一次有效配置继续生效");
        }
        // ② Nacos 写入（v1 OpenAPI，与 M8 Prompt 同模式）
        boolean nacosOk = publishToNacos(target);
        if (!nacosOk) {
            failPublish(record, "Nacos 写入失败（正式配置未变更）");
            throw new BizException("Nacos 写入失败；最后一次有效配置继续生效");
        }
        // ③ 有界回读一致性校验（≤5s）
        if (!readBackConsistent(target, 5_000)) {
            failPublish(record, "Nacos 回读不一致（超时），发布中止（正式配置未变更）");
            throw new BizException("Nacos 回读校验失败；最后一次有效配置继续生效");
        }
        // ④ 热生效（进程内原子替换 + Redis 恢复）
        if (!dynamic.apply(target, "PUBLISH:" + record.getPublishVersion())) {
            failPublish(record, "参数合法性终检失败（应不会发生），正式配置未变更");
            throw new BizException("参数生效失败；最后一次有效配置继续生效");
        }
        // ⑤ DB 生效值 + 草稿清理（version 条件更新）
        for (SysConfigParam p : toPublish) {
            SysConfigParam upd = new SysConfigParam();
            upd.setEffectiveValue(p.getDraftValue());
            upd.setDraftValue(null);
            upd.setVersion(p.getVersion() + 1);
            upd.setLastPublishedBy(adminId);
            upd.setPublishTime(LocalDateTime.now());
            upd.setUpdateTime(LocalDateTime.now());
            int n = paramMapper.update(upd, Wrappers.<SysConfigParam>lambdaUpdate()
                    .eq(SysConfigParam::getId, p.getId())
                    .eq(SysConfigParam::getVersion, p.getVersion()));
            if (n != 1) {
                log.warn("[config.params] 发布时版本冲突 param={}（生效值以 DB 为准，需重新发布）", p.getParamKey());
            }
        }
        // ⑥ 运行时生效验证（本进程读取确认；跨实例经 30s 轮询，如实标注验证范围）
        boolean runtimeOk = target.equals(dynamic.snapshot());
        SysConfigPublish done = new SysConfigPublish();
        done.setPublishStatus(SysConfigPublish.ST_SUCCESS);
        done.setRuntimeVerified(runtimeOk ? SysConfigPublish.RT_VERIFIED : SysConfigPublish.RT_UNVERIFIED);
        done.setEffectiveTime(LocalDateTime.now());
        publishMapper.update(done, Wrappers.<SysConfigPublish>lambdaUpdate()
                .eq(SysConfigPublish::getId, record.getId()));

        audit.write(adminId, adminName, "CONFIG", "PARAM_PUBLISH",
                "SYS_CONFIG", record.getId(),
                "{\"version\":" + record.getPublishVersion() + ",\"keys\":" + record.getParamKeys() + "}");
        return Map.of("publishId", String.valueOf(record.getId()),
                "publishVersion", record.getPublishVersion(),
                "runtimeVerified", runtimeOk);
    }

    /** 发布历史 */
    public List<SysConfigPublish> publishes(long page, long size) {
        return publishMapper.selectList(Wrappers.<SysConfigPublish>lambdaQuery()
                .orderByDesc(SysConfigPublish::getCreateTime)
                .last("LIMIT " + Math.min(size, 50) + " OFFSET " + (page - 1) * Math.min(size, 50)));
    }

    // ------------------------------------------------------------------

    private int nextPublishVersion() {
        SysConfigPublish latest = publishMapper.selectOne(Wrappers.<SysConfigPublish>lambdaQuery()
                .orderByDesc(SysConfigPublish::getPublishVersion)
                .last("LIMIT 1"));
        return latest == null ? 1 : latest.getPublishVersion() + 1;
    }

    private SysConfigParam requireParam(Long id) {
        SysConfigParam p = paramMapper.selectById(id);
        if (p == null) {
            throw new BizException("参数不存在");
        }
        return p;
    }

    private void checkVersion(SysConfigParam p, Integer expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(p.getVersion())) {
            throw new BizException("配置已被他人修改（版本冲突），请刷新后重试");
        }
    }

    /** 范围校验（value_type + min/max；非法直接拒绝） */
    private void validateValue(SysConfigParam p, String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(p.getParamName() + "：值不能为空");
        }
        try {
            if ("INT".equals(p.getValueType())) {
                long v = Long.parseLong(value.trim());
                if (v < Long.parseLong(p.getMinValue()) || v > Long.parseLong(p.getMaxValue())) {
                    throw new BizException(p.getParamName() + "：必须在 " + p.getMinValue()
                            + " ~ " + p.getMaxValue() + " 之间");
                }
            } else {
                double v = Double.parseDouble(value.trim());
                if (v < Double.parseDouble(p.getMinValue()) || v > Double.parseDouble(p.getMaxValue())) {
                    throw new BizException(p.getParamName() + "：必须在 " + p.getMinValue()
                            + " ~ " + p.getMaxValue() + " 之间");
                }
            }
        } catch (NumberFormatException ex) {
            throw new BizException(p.getParamName() + "：必须是合法的 " + p.getValueType() + " 值");
        }
    }

    private boolean publishToNacos(Map<String, String> params) {
        try {
            String content = om.writeValueAsString(Map.of("params", params));
            String resp = RestClient.builder().baseUrl("http://" + nacosAddr).build()
                    .post()
                    .uri(uri -> uri.path("/nacos/v1/cs/configs")
                            .queryParam("dataId", DynamicAiParams.DATA_ID)
                            .queryParam("group", DynamicAiParams.GROUP)
                            .build())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("content=" + java.net.URLEncoder.encode(content, java.nio.charset.StandardCharsets.UTF_8))
                    .retrieve()
                    .body(String.class);
            return "true".equals(String.valueOf(resp).trim());
        } catch (Exception ex) {
            log.warn("[config.params] Nacos 发布失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 有界回读一致性校验（≤5s，容忍 Derby 秒级持久化延迟；与 M8 Prompt 同模式） */
    private boolean readBackConsistent(Map<String, String> target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Map<String, String> remote = dynamic.fetchFromNacos();
            if (remote != null) {
                boolean ok = true;
                for (Map.Entry<String, String> e : target.entrySet()) {
                    if (!e.getValue().equals(remote.get(e.getKey()))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return true;
                }
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void failPublish(SysConfigPublish record, String error) {
        SysConfigPublish upd = new SysConfigPublish();
        upd.setPublishStatus(SysConfigPublish.ST_FAILED);
        upd.setError(error);
        publishMapper.update(upd, Wrappers.<SysConfigPublish>lambdaUpdate()
                .eq(SysConfigPublish::getId, record.getId()));
        log.warn("[config.params] 发布失败: {}", error);
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
