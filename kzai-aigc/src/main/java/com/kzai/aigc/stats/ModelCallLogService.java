package com.kzai.aigc.stats;

import com.kzai.aigc.stats.entity.AiModelCallLog;
import com.kzai.aigc.stats.mapper.AiModelCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * M13 模型调用日志写入（fire-and-forget）：
 * 写入失败仅 WARN 告警（可观察），绝不阻断用户正常 AI 对话（用户批准方案）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCallLogService {

    private final AiModelCallLogMapper mapper;

    public void record(AiModelCallLog row) {
        try {
            if (row.getCreateTime() == null) {
                row.setCreateTime(java.time.LocalDateTime.now());
            }
            mapper.insert(row);
        } catch (RuntimeException ex) {
            log.warn("[model.call] 调用日志写入失败（不阻断业务）: source={} provider={} err={}",
                    row.getSource(), row.getProvider(), ex.getMessage());
        }
    }
}
