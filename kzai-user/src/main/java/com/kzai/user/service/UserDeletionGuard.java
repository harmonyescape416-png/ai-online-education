package com.kzai.user.service;

/**
 * 删除用户前的关联业务校验（需求 3.2.5.2：删除前检查课程、订单、会话等关联数据）。
 * <p>
 * 每个已建成的业务服务注册一个实现；返回大于 0 表示存在关联，禁止删除。
 * <p>
 * M2 遗留修复③：{@link Constants#REQUIRED_DELETION_BINDINGS} 中列出的每一类关联都必须有实现，
 * 缺失的类型按"无法确认是否存在业务数据"处理，直接拒绝删除（fail-closed）。
 */
public interface UserDeletionGuard {

    /** 关联业务名称，用于错误提示 */
    String bindingName();

    /** 关联业务类型，需与 Constants.ASSOC_* 一致 */
    String bindingType();

    /**
     * 关联记录数。
     *
     * @return 大于 0 表示有关联；负数表示校验服务不可用（保守起见按有关联处理）
     */
    long count(Long userId);
}
