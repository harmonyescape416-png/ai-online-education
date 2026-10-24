package com.kzai.learning.points;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointSettlementJob {

    private final PointService points;

    @Scheduled(cron = "0 5 0 1 * ?", zone = "Asia/Shanghai")
    public void settlePreviousMonth() {
        try {
            points.settle(null);
        } catch (RuntimeException ex) {
            log.warn("[points] 月榜结算失败，下次重试 {}", ex.getMessage());
        }
    }

    /** 每分钟重试发分失败的补偿事件（见 PointService.award 的补偿队列）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void retryPendingAwards() {
        List<PointPendingAward> pending = points.loadPending(50);
        for (PointPendingAward row : pending) {
            try {
                int gained = points.awardStrict(row.getUserId(), row.getSource(), row.getEventKey(), row.getBizDate());
                // gained==0 属规则性不发（已达上限/规则关闭/事件已存在），视为终结，避免无限重试
                points.markPendingDone(row.getId(), gained > 0 ? "awarded " + gained : "skipped by rule");
                if (gained > 0) {
                    log.info("[points] 补偿发分成功 event={} +{}", row.getEventKey(), gained);
                }
            } catch (RuntimeException ex) {
                int next = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
                points.markPendingFailed(row.getId(), next, ex.toString());
                log.warn("[points] 补偿重试失败({}/{}) event={} {}", next, 5, row.getEventKey(), ex.toString());
            }
        }
    }
}
