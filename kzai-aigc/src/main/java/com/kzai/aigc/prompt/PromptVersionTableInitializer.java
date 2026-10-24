package com.kzai.aigc.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.nio.charset.StandardCharsets;

/** M15.19-D：prompt_version 启动幂等建表（DDL 归档 deploy/migrations/V20261021_prompt_version.sql） */
@Component
@RequiredArgsConstructor
@Slf4j
public class PromptVersionTableInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String ddl = new String(FileCopyUtils.copyToByteArray(
                    new ClassPathResource("db/prompt_version.sql").getInputStream()), StandardCharsets.UTF_8);
            for (String stmt : ddl.split(";")) {
                if (!stmt.isBlank()) {
                    jdbcTemplate.execute(stmt);
                }
            }
            log.info("[aigc] prompt_version 表就绪（幂等建表）");
        } catch (Exception e) {
            log.error("[aigc] prompt_version 建表失败（可手工执行 deploy/migrations 脚本）", e);
        }
    }
}
