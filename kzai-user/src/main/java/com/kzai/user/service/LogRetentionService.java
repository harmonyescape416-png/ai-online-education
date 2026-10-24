package com.kzai.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.user.entity.OperationLog;
import com.kzai.user.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * M13 操作日志 90 天保留策略（原始需求）：
 * <ul>
 *   <li>每日扫描 create_time 早于 90 天的记录，按 500 行/批删除（避免一次删过多）；</li>
 *   <li>删除范围严格限于目标日志（仅 sys_operation_log 过期行）；不触碰业务记录与未到期记录；</li>
 *   <li>失败（异常）下次调度自动重试；清理结果以 RETENTION 结构化日志留痕
 *       （时间、数量、截止点），清理动作本身写入当前日志（时间晚于截止点，不会被本批删除，
 *       无递归审计问题）；</li>
 *   <li>ai_model_call_log 的 90 天清理由 aigc 服务同策略执行（表归属服务自治）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogRetentionService {

    private final OperationLogMapper mapper;

    @Scheduled(fixedDelay = 24 * 3600_000L, initialDelay = 90_000)
    public void retain90Days() {
        LocalDateTime expire = LocalDateTime.now().minusDays(90);
        long deleted = 0;
        try {
            while (true) {
                List<OperationLog> batch = mapper.selectList(Wrappers.<OperationLog>lambdaQuery()
                        .lt(OperationLog::getCreateTime, expire)
                        .select(OperationLog::getId)
                        .last("LIMIT 500"));
                if (batch.isEmpty()) {
                    break;
                }
                deleted += mapper.deleteBatchIds(batch.stream().map(OperationLog::getId).toList());
                if (batch.size() < 500) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("[retention] 操作日志清理失败（下轮自动重试）: {}", ex.getMessage());
            return;
        }
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger("RETENTION")
                    .info("retention=OPERATION_LOG table=sys_operation_log deleted={} before={}", deleted, expire);
        }
    }
}
