package com.kzai.user.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.nio.charset.StandardCharsets;

/**
 * M15.15 方案 A（用户批准）：user_profile_ext 启动幂等建表。
 * DDL 同时归档于 deploy/migrations/V20261021_user_profile_ext.sql（人工执行与审计用）。
 * 不修改原始 12 张核心表；CREATE TABLE IF NOT EXISTS 幂等可重复执行。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserProfileExtTableInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String ddl = new String(FileCopyUtils.copyToByteArray(
                    new ClassPathResource("db/user_profile_ext.sql").getInputStream()), StandardCharsets.UTF_8);
            jdbcTemplate.execute(ddl);
            // M15.15：存量表补列（幂等——列已存在时忽略异常）
            try {
                jdbcTemplate.execute("ALTER TABLE user_profile_ext ADD COLUMN nickname_updated_at DATETIME NULL "
                        + "COMMENT '昵称最近修改时间'");
            } catch (Exception dup) {
                log.info("[user] nickname_updated_at 列已存在，跳过补列");
            }
            log.info("[user] user_profile_ext 表就绪（幂等建表）");
        } catch (Exception e) {
            log.error("[user] user_profile_ext 建表失败（请检查数据库权限或手工执行 deploy/migrations 脚本）", e);
        }
    }
}
