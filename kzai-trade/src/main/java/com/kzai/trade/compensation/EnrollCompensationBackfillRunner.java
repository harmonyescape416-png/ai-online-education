package com.kzai.trade.compensation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.trade.entity.Order;
import com.kzai.trade.entity.OrderDetail;
import com.kzai.trade.mapper.OrderDetailMapper;
import com.kzai.trade.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * M15.22：兼容回填——启动时为已支付但缺补偿记录的旧订单补登记（幂等）。
 * 核实学习档案：learning 可用→查 lesson（有=SUCCESS/无=PENDING 补偿）；learning 不可用→PENDING（待核实）。
 * 不再默认 SUCCESS。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EnrollCompensationBackfillRunner implements ApplicationRunner {

    private final EnrollCompensationMapper mapper;
    private final OrderMapper orderMapper;
    private final OrderDetailMapper detailMapper;
    private final org.springframework.beans.factory.ObjectProvider<com.kzai.trade.client.InternalLearningClient> learningProvider;

    @Override
    public void run(ApplicationArguments args) {
        try {
            var paidOrders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                    .eq(Order::getStatus, 2));
            int backfilled = 0;
            for (var o : paidOrders) {
                var details = detailMapper.selectList(Wrappers.<OrderDetail>lambdaQuery()
                        .eq(OrderDetail::getOrderId, o.getId()));
                for (var d : details) {
                    Long exists = mapper.selectCount(Wrappers.<EnrollCompensationEntity>lambdaQuery()
                            .eq(EnrollCompensationEntity::getOrderId, o.getId())
                            .eq(EnrollCompensationEntity::getCourseId, d.getCourseId()));
                    if (exists != null && exists > 0) {
                        continue;
                    }
                    // M15.22：核实学习档案（learning 可查→有 lesson=SUCCESS/无=PENDING 补偿；不可用=PENDING 待核实）
                    String status = "PENDING";
                    try {
                        var lr = learningProvider.getObject().existsLesson(java.util.Map.of(
                                "userId", String.valueOf(o.getUserId()),
                                "courseId", String.valueOf(d.getCourseId())));
                        boolean hasLesson = lr != null && Integer.valueOf(200).equals(lr.getCode())
                                && Boolean.TRUE.equals(lr.getData());
                        status = hasLesson ? "SUCCESS" : "PENDING";
                    } catch (Exception le) {
                        status = "PENDING"; // learning 不可用：待核实（不默认成功）
                    }
                    EnrollCompensationEntity e = new EnrollCompensationEntity();
                    e.setOrderId(o.getId());
                    e.setCourseId(d.getCourseId());
                    e.setUserId(o.getUserId());
                    e.setAttempts(0);
                    e.setStatus(status);
                    LocalDateTime now = LocalDateTime.now();
                    e.setCreateTime(now);
                    e.setUpdateTime(now);
                    mapper.insert(e);
                    backfilled++;
                }
            }
            if (backfilled > 0) {
                log.info("[compensation] 存量已支付订单补偿记录回填 {} 条（含核实状态）", backfilled);
            }
        } catch (Exception ex) {
            log.error("[compensation] 回填失败（不影响启动）", ex);
        }
    }
}
