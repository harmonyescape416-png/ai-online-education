package com.kzai.common.security;

/**
 * token 版本号的权威存储（M2 遗留修复②）。
 * <p>
 * 此前版本号只写 Redis，Redis 键丢失后旧 token 会恢复有效；
 * 现由本接口落到 MySQL（用户服务持有数据），Redis 退化为缓存。
 */
public interface TokenVersionStore {

    /** 当前版本号；无记录时为 0。读取失败必须抛出异常，由调用方按 fail-closed 处理 */
    int current(Long userId);

    /** 版本号自增，使该用户全部旧 token 失效；返回自增后的值 */
    int bump(Long userId);
}
