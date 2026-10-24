package com.kzai.course.service;

/**
 * 课程学习权益查询（付费内容访问控制用）。
 * <p>
 * 课程服务本身不持有订单数据：默认实现一律返回 false（未取得权益），
 * 即付费课程资料不对普通学员开放；M4 交易服务上线后由 Feign 实现按"已支付订单"判定。
 */
public interface EntitlementProvider {

    /**
     * 指定用户是否已取得该课程的学习权益（已支付且未退款）。
     */
    boolean hasPaidEntitlement(Long userId, Long courseId);
}
