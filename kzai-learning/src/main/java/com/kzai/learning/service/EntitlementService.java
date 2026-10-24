package com.kzai.learning.service;

import com.kzai.common.constant.Constants;
import com.kzai.learning.client.TradeClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 学习权益判定（M5）。
 * <p>
 * 判定依据（全部来自服务端）：
 * <ul>
 *   <li>当前登录用户（取自网关认证上下文，前端无法伪造）；</li>
 *   <li>课程ID + 课程可见性（已上架 + 分类启用）；</li>
 *   <li>免费课程：free=1 或 price=0 → 允许入课，不生成任何订单；</li>
 *   <li>付费课程：由交易服务依据真实订单判定（订单主体状态 + 明细状态 + 退款状态 + 有效期），
 *       学习服务不访问订单表，不采信前端传入的 paid / orderId / 任意状态值；</li>
 *   <li>交易服务不可用 → 一律判定为无权益（fail-closed）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final TradeClient tradeClient;

    /**
     * 判定学习权益。
     *
     * @param courseOutline 课程服务返回的实时课程快照，不得为 null
     */
    public EntitlementDecision resolve(Long userId, CourseOutlineDTO courseOutline) {
        EntitlementDecision decision = new EntitlementDecision();
        decision.setAllowed(false);
        decision.setSource(Constants.ENTITLEMENT_NONE);
        decision.setTradeAvailable(Boolean.TRUE);

        if (courseOutline == null || courseOutline.getId() == null) {
            decision.setReason("课程不存在");
            return decision;
        }
        if (!Boolean.TRUE.equals(courseOutline.getStudentVisible())) {
            decision.setReason("课程未上架或所属分类已禁用，暂不可学习");
            return decision;
        }
        if (isFree(courseOutline)) {
            decision.setAllowed(true);
            decision.setSource(Constants.ENTITLEMENT_FREE);
            decision.setReason("免费课程");
            return decision;
        }
        // 付费课程：权益只能来自真实订单
        try {
            var result = tradeClient.entitlementDetail(userId, courseOutline.getId());
            EntitlementDecision paid = result == null ? null : result.getData();
            if (paid == null) {
                decision.setTradeAvailable(Boolean.FALSE);
                decision.setReason("学习权益校验服务暂时不可用，请稍后重试");
                return decision;
            }
            return paid;
        } catch (Exception e) {
            // 交易服务不可用：受保护资源必须安全拒绝，不得默认放行
            log.warn("[learning] trade service unavailable, deny entitlement: userId={} courseId={} msg={}",
                    userId, courseOutline.getId(), e.getMessage());
            decision.setTradeAvailable(Boolean.FALSE);
            decision.setReason("学习权益校验服务暂时不可用，请稍后重试");
            return decision;
        }
    }

    /** 免费课程判定：free=1 或价格 0 分（沿用 M3/M4 既有口径） */
    public static boolean isFree(CourseOutlineDTO course) {
        if (course == null) {
            return false;
        }
        return Integer.valueOf(1).equals(course.getFree())
                || course.getPrice() == null
                || course.getPrice() == 0;
    }
}
