package com.kzai.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.course.client.TradeClient;
import com.kzai.course.client.UserNotificationClient;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseAudit;
import com.kzai.course.entity.SysEventOutbox;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.mapper.SysEventOutboxMapper;
import com.kzai.course.notify.NotificationOutboxWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M11 补充验收（course 侧，M12 阶段任务书第一部分）：
 * <b>真实 MySQL</b> 下「课程审核通过 → 审核记录 + Outbox 事件同库同事务」证据。
 * <p>
 * 默认不执行（需显式 {@code KZAI_MYSQL_TEST=true}）。
 * <ol>
 *   <li>approve() 事务内：course.status→已上架 + course_audit(APPROVED) + Outbox 事件三者同事务可见；</li>
 *   <li><b>回滚后三者均不存在</b>（新连接复核：课程保持待上架、无审核记录、无事件、无通知）；</li>
 *   <li>提交路径：事件持久存在且 eventKey=COURSE_APPROVED:{auditId}（以真实审核记录为依据）；</li>
 *   <li>驳回路径：不产生 COURSE_APPROVED 事件；重复通过被状态机拒绝且不新增事件。</li>
 * </ol>
 * 夹具使用 7775xxxxx 段 ID 隔离，测试后清理。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.sleuth.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KZAI_MYSQL_TEST", matches = "true")
class AuditNotifyTxMysqlIT {

    private static final long ADMIN_ID = 7775501L;
    private static final long TEACHER_ID = 7775502L;

    @Autowired
    private AuditService auditService;
    @Autowired
    private CourseMapper courseMapper;
    @Autowired
    private SysEventOutboxMapper outboxMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private TradeClient tradeClient;
    /** 测试自建 Worker 的投递桩；替换上下文 Worker Bean（吸收 @Scheduled 轮询） */
    @MockBean
    private UserNotificationClient notificationClient;
    @MockBean
    private NotificationOutboxWorker scheduledWorkerGuard;

    private TransactionTemplate tx;
    private long courseId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        UserContext.set(ADMIN_ID, "mysql-it-admin", 1, List.of("ADMIN"), "it-audit");
        System.out.println("[AuditTx-IT] jdbc.url = " + jdbcUrl());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (courseId > 0) {
            exec("DELETE FROM sys_event_outbox WHERE biz_type='COURSE' AND biz_id=?", String.valueOf(courseId));
            exec("DELETE FROM course_audit WHERE course_id=?", courseId);
            exec("DELETE FROM course WHERE id=?", courseId);
            exec("DELETE FROM sys_notification WHERE biz_type='COURSE' AND biz_id=?", String.valueOf(courseId));
        }
        UserContext.clear();
        courseId = 0;
    }

    // ------------------------------------------------------------------
    // 1) 回滚：审核通过后强制回滚 → 课程状态/审核记录/事件全部不存在
    // ------------------------------------------------------------------
    @Test
    void approveRollback_courseAuditAndOutbox_allAbsent() throws Exception {
        courseId = newCourse();

        tx.execute(status -> {
            auditService.approve(courseId);
            Course inTx = courseMapper.selectById(courseId);
            assertEquals(Integer.valueOf(Constants.COURSE_STATUS_ONLINE), inTx.getStatus(),
                    "事务内课程应为已上架");
            CourseAudit audit = auditService.latestAudit(courseId);
            assertNotNull(audit);
            SysEventOutbox row = outboxMapper.selectOne(Wrappers.<SysEventOutbox>lambdaQuery()
                    .eq(SysEventOutbox::getEventKey, "COURSE_APPROVED:" + audit.getId()));
            assertNotNull(row, "事务内应能看到审核通过事件（同库同事务）");
            status.setRollbackOnly(); // 模拟审核事务回滚（如后续校验失败）
            return null;
        });

        // 新连接复核（事务已真实回滚）
        Course after = courseMapper.selectById(courseId);
        assertNotNull(after, "课程夹具行由测试外插入，不受业务回滚影响");
        assertEquals(Integer.valueOf(Constants.COURSE_STATUS_PENDING), after.getStatus(),
                "回滚后课程必须保持待上架");
        assertFalse(exists("SELECT COUNT(*) FROM course_audit WHERE course_id=? AND audit_status=2",
                        courseId),
                "回滚后不得存在「审核通过」记录（夹具的待审核记录应保留）");
        assertFalse(exists("SELECT COUNT(*) FROM sys_event_outbox WHERE biz_type='COURSE' AND biz_id=?",
                        String.valueOf(courseId)),
                "回滚后不得存在审核通过事件");
        assertFalse(exists("SELECT COUNT(*) FROM sys_notification WHERE biz_type='COURSE' AND biz_id=?",
                        String.valueOf(courseId)),
                "回滚后不得存在通知");
    }

    // ------------------------------------------------------------------
    // 2) 提交：事件持久存在，eventKey 以真实审核记录 ID 为依据
    // ------------------------------------------------------------------
    @Test
    void approveCommit_outboxEventPersistsWithAuditIdKey() throws Exception {
        courseId = newCourse();
        tx.executeWithoutResult(status -> auditService.approve(courseId));

        Course after = courseMapper.selectById(courseId);
        assertEquals(Integer.valueOf(Constants.COURSE_STATUS_ONLINE), after.getStatus());
        CourseAudit audit = auditService.latestAudit(courseId);
        assertNotNull(audit, "提交后审核记录持久存在");
        assertTrue(exists("SELECT COUNT(*) FROM sys_event_outbox WHERE event_key=?",
                        "COURSE_APPROVED:" + audit.getId()),
                "事件 eventKey 必须为 COURSE_APPROVED:{auditId}（真实审核记录依据）");
        SysEventOutbox row = outboxMapper.selectOne(Wrappers.<SysEventOutbox>lambdaQuery()
                .eq(SysEventOutbox::getEventKey, "COURSE_APPROVED:" + audit.getId()));
        assertEquals(Long.valueOf(TEACHER_ID), row.getRecipientId(), "收件人必须为课程负责人");
    }

    // ------------------------------------------------------------------
    // 3) 驳回：不产生审核通过事件
    // ------------------------------------------------------------------
    @Test
    void reject_producesNoEvent() throws Exception {
        courseId = newCourse();
        auditService.reject(courseId, "ITTX 驳回原因");
        CourseAudit audit = auditService.latestAudit(courseId);
        assertNotNull(audit);
        assertEquals(Constants.AUDIT_REJECTED, audit.getAuditStatus());
        assertFalse(exists("SELECT COUNT(*) FROM sys_event_outbox WHERE biz_type='COURSE' AND biz_id=?",
                        String.valueOf(courseId)),
                "驳回不得产生 COURSE_APPROVED 事件");
    }

    // ------------------------------------------------------------------
    // 4) 状态机：重复通过被拒绝且不产生新事件
    // ------------------------------------------------------------------
    @Test
    void secondApprove_rejectedByStateMachine() throws Exception {
        courseId = newCourse();
        auditService.approve(courseId);
        long eventsBefore = count("SELECT COUNT(*) FROM sys_event_outbox WHERE biz_type='COURSE' AND biz_id=?",
                String.valueOf(courseId));
        assertThrows(com.kzai.common.exception.BizException.class, () -> auditService.approve(courseId),
                "已通过课程再次审核必须被状态机拒绝");
        long eventsAfter = count("SELECT COUNT(*) FROM sys_event_outbox WHERE biz_type='COURSE' AND biz_id=?",
                String.valueOf(courseId));
        assertEquals(eventsBefore, eventsAfter, "拒绝路径不得新增事件");
    }

    // ------------------------------------------------------------------
    // fixtures & helpers
    // ------------------------------------------------------------------

    private long newCourse() throws Exception {
        long id = 77756000L + (System.nanoTime() % 1_000_000);
        exec("INSERT INTO course (id, name, course_type, free, price, status, section_num, score,"
                        + " creater, deleted, create_time, update_time)"
                        + " VALUES (?, 'ITTX 审核回滚课程', 1, 1, 0, 1, 0, 0, ?, 0, NOW(), NOW())",
                id, TEACHER_ID);
        // 插入待审核记录（提交审核后的状态）
        exec("INSERT INTO course_audit (id, course_id, audit_status, reason, auditor_id, auditor_name,"
                        + " create_time, update_time) VALUES (?, ?, 1, '提交审核', ?, 'it-teacher', NOW(), NOW())",
                id + 1, id, TEACHER_ID);
        return id;
    }

    private String jdbcUrl() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getURL();
        } catch (Exception ex) {
            return "unavailable: " + ex.getMessage();
        }
    }

    private boolean exists(String sql, Object... args) throws Exception {
        return count(sql, args) > 0;
    }

    private long count(String sql, Object... args) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void exec(String sql, Object... args) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
