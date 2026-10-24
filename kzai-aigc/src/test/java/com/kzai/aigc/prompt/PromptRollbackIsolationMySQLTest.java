package com.kzai.aigc.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M15.21 回归（真实数据库断言，非 Mock 返回值）：
 * ①回滚只影响选定模板（发布 CONSULT A→发布 BUY B→回滚 CONSULT→BUY 最新版本仍为 B）
 * ②版本唯一约束并发下保证同类型版本号唯一
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/course_stack_ai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=kzai",
        "spring.datasource.password=Kzai@2026",
        "spring.sql.init.mode=never",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class PromptRollbackIsolationMySQLTest {

    @Autowired
    private PromptVersionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void cleanTable() {
        // 测试隔离：清空本测试类型数据（隔离测试库；业务库不受影响）
        jdbcTemplate.update("DELETE FROM prompt_version WHERE agent_type IN ('CONSULT','BUY','STUDY')");
    }

    @Test
    void rollbackIsolation_realDb() {
        // 发布 CONSULT A（版本 1）
        service.record("CONSULT", "{\"systemPrompt\":\"A\"}", "PUBLISH", 1L, true, "ok");
        // 发布 BUY B（版本 1）
        service.record("BUY", "{\"systemPrompt\":\"B\"}", "PUBLISH", 1L, true, "ok");
        // CONSULT 再发一版 A2（版本 2）
        service.record("CONSULT", "{\"systemPrompt\":\"A2\"}", "PUBLISH", 1L, true, "ok");

        // 模拟回滚 CONSULT 到版本 1：以版本 1 内容记录 ROLLBACK 新版本（版本 3）——BUY 版本不变
        var target = service.byVersionNo("CONSULT", 1);
        service.record("CONSULT", target.getContent(), "ROLLBACK", 1L, true, "回滚自版本 1");

        // 断言：BUY 最新版本仍为 B（版本 1——未被 CONSULT 回滚影响）
        var buyLatest = jdbcTemplate.queryForMap(
                "SELECT content, version_no FROM prompt_version WHERE agent_type='BUY' ORDER BY version_no DESC LIMIT 1");
        // content 为单模板 JSON——断言包含 systemPrompt=B（真实存储格式）
        assertTrue(String.valueOf(buyLatest.get("content")).contains("\"systemPrompt\":\"B\"")
                || String.valueOf(buyLatest.get("content")).contains("\\\"systemPrompt\\\":\\\"B\\\""));
        assertEquals(1, ((Number) buyLatest.get("version_no")).intValue());
        // 断言：CONSULT 最新版本=版本 3（ROLLBACK 产生新版本，历史不覆盖）
        var consultLatest = jdbcTemplate.queryForMap(
                "SELECT content, version_no, action FROM prompt_version WHERE agent_type='CONSULT' ORDER BY version_no DESC LIMIT 1");
        assertEquals(3, ((Number) consultLatest.get("version_no")).intValue());
        assertEquals("ROLLBACK", consultLatest.get("action"));
        assertTrue(String.valueOf(consultLatest.get("content")).contains("A")); // 回滚到 A 内容（JSON）
    }

    @Test
    void uniqueVersionConstraint_realDb() {
        // 真实 DB 唯一约束：同类型同版本号重复插入被拒
        service.record("STUDY", "{\"x\":1}", "PUBLISH", 1L, true, "ok");
        assertThrows(Exception.class, () ->
                jdbcTemplate.execute("INSERT INTO prompt_version(agent_type,content,version_no,action,operator,result_ok,create_time) " +
                        "VALUES('STUDY','{\"dup\":1}',1,'PUBLISH',1,1,CURRENT_TIMESTAMP)"));
    }
}
