package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.client.TradeClient;
import com.kzai.learning.client.UserClient;
import com.kzai.learning.client.UserNotificationClient;
import com.kzai.learning.dto.QuestionReplyRequest;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.SysEventOutbox;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import com.kzai.learning.mapper.SysEventOutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 补充验收（M12 阶段任务书第一部分）：<b>真实 MySQL</b> 事务边界与通知可靠性证据。
 * <p>
 * 默认不执行（需显式 {@code KZAI_MYSQL_TEST=true}），直连 application.yml 的
 * {@code jdbc:mysql://127.0.0.1:3306/course_stack_ai}，测试输出打印真实 JDBC URL。
 * 全部基于真实库表 + 真实 Service/Mapper（Feign 用桩，调度 Worker 用桩替换后手工驱动）。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>教师回复 + Outbox 同事务：事务内可见 → <b>回滚后两者均不存在</b>（新连接复核）；</li>
 *   <li>提交路径：业务提交 → Outbox 行持久存在 → Worker 投递成功置 SUCCESS；</li>
 *   <li>eventKey 唯一约束：重复入队幂等；同键直接插入被数据库拒绝；</li>
 *   <li>sys_notification UNIQUE(recipient_id, event_key)：重复投递被数据库拒绝；</li>
 *   <li>领取原子性：同一行两次条件领取仅一次成功（影响行数 1/0）；</li>
 *   <li>租约：未到期不可接管、到期可接管且 attempt+1、SUCCESS 永不重新领取；</li>
 *   <li>FAILED 保留 + 安全重试（人工重置 PENDING）路径。</li>
 * </ol>
 * eventKey 一律使用 {@code ITTX:}/{@code QUESTION_REPLY:ITTX} 前缀隔离，测试后全部清理。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.sleuth.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KZAI_MYSQL_TEST", matches = "true")
class NotificationTxMysqlIT {

    private static final long TEACHER_ID = 7771101L;
    private static final long STUDENT_ID = 7771102L;
    private static final long IT_COURSE_ID = 77733101L;

    @Autowired
    private TeacherQuestionService teacherService;
    @Autowired
    private NotificationOutboxService outboxService;
    @Autowired
    private SysEventOutboxMapper outboxMapper;
    @Autowired
    private InteractionQuestionMapper questionMapper;
    @Autowired
    private InteractionReplyMapper replyMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private CourseClient courseClient;
    @MockBean
    private TradeClient tradeClient;
    @MockBean
    private UserClient userClient;
    /** 测试自建 Worker 的投递桩；同时替换上下文 Worker Bean（吸收 @Scheduled 轮询） */
    @MockBean
    private UserNotificationClient notificationClient;
    @MockBean
    private NotificationOutboxWorker scheduledWorkerGuard;

    private TransactionTemplate tx;
    private final List<Long> questionIds = new ArrayList<>();
    private final List<Long> outboxIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        UserContext.set(TEACHER_ID, "mysql-it-teacher", Constants.USER_TYPE_TEACHER,
                List.of(Constants.ROLE_TEACHER, "ADMIN"), "it-tx");
        System.out.println("[NotifyTx-IT] jdbc.url = " + jdbcUrl());
    }

    @AfterEach
    void tearDown() throws Exception {
        // 回滚用例无残留；提交/直插用例在此统一清理（真实连接）
        exec("DELETE FROM interaction_reply_extra WHERE reply_id IN "
                + "(SELECT id FROM interaction_reply WHERE question_id IN "
                + "(SELECT id FROM interaction_question WHERE course_id=?))", IT_COURSE_ID);
        exec("DELETE FROM interaction_reply WHERE question_id IN "
                + "(SELECT id FROM interaction_question WHERE course_id=?)", IT_COURSE_ID);
        exec("DELETE FROM interaction_question WHERE course_id=?", IT_COURSE_ID);
        exec("DELETE FROM sys_notification WHERE event_key LIKE 'ITTX:%'");
        // 提交路径产生的事件/通知（收件人为 IT 专属账号，按收件人清理）
        exec("DELETE FROM sys_event_outbox WHERE recipient_id=? AND type='QUESTION_REPLY' "
                + "AND create_time >= NOW() - INTERVAL 30 MINUTE", STUDENT_ID);
        exec("DELETE FROM sys_notification WHERE recipient_id=? AND type='QUESTION_REPLY' "
                + "AND create_time >= NOW() - INTERVAL 30 MINUTE", STUDENT_ID);
        UserContext.clear();
        questionIds.clear();
        outboxIds.clear();
    }

    // ------------------------------------------------------------------
    // 1) 业务事务回滚 → 回复与 Outbox 事件都不存在（真实回滚证据）
    // ------------------------------------------------------------------
    @Test
    void businessRollback_replyAndOutboxEvent_bothAbsent() throws Exception {
        long questionId = newQuestion();
        QuestionReplyRequest req = new QuestionReplyRequest();
        req.setContent("ITTX 教师回复（将被回滚）");

        String eventKey = tx.execute(status -> {
            Map<String, Object> out = teacherService.reply(TEACHER_ID,
                    List.of(Constants.ROLE_TEACHER, "ADMIN"), questionId, req);
            assertEquals(Boolean.TRUE, out.get("replied"));
            // 同一事务内：回复与 Outbox 事件互相可见（同库同事务）
            InteractionReply reply = replyMapper.selectList(Wrappers.<InteractionReply>lambdaQuery()
                    .eq(InteractionReply::getQuestionId, questionId)).get(0);
            SysEventOutbox row = outboxMapper.selectOne(Wrappers.<SysEventOutbox>lambdaQuery()
                    .eq(SysEventOutbox::getEventKey, "QUESTION_REPLY:" + reply.getId()));
            assertNotNull(row, "事务内应能看到 Outbox 事件行（同库同事务写入）");
            status.setRollbackOnly(); // 模拟业务回滚（回复后置校验失败等场景）
            return row.getEventKey();
        });
        assertNotNull(eventKey);

        // 新连接复核（事务已真实回滚）：
        assertFalse(exists("SELECT COUNT(*) FROM interaction_reply WHERE question_id=?", questionId),
                "业务事务回滚后回复必须不存在");
        assertFalse(exists("SELECT COUNT(*) FROM sys_event_outbox WHERE event_key=?", eventKey),
                "业务事务回滚后不得存在 Outbox 事件");
        assertFalse(exists("SELECT COUNT(*) FROM sys_notification WHERE event_key=?", eventKey),
                "业务事务回滚后不得存在通知");
    }

    // ------------------------------------------------------------------
    // 2) 提交路径：业务提交 → Outbox 行持久存在；Worker 投递成功 → SUCCESS
    // ------------------------------------------------------------------
    @Test
    void commitPath_outboxRowPersists_workerDelivers_success() throws Exception {
        // 生产 learning 服务（独立进程）的 Worker 也在轮询同一张表；若其抢先领取并投递，
        // 本测试换新事件重试（最多 5 次），直到「本测试 Worker 以 Mock 桩完成投递」。
        long myPid = ProcessHandle.current().pid();
        org.mockito.Mockito.clearInvocations(notificationClient);
        AssertionError lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            long questionId = newQuestion();
            QuestionReplyRequest req = new QuestionReplyRequest();
            req.setContent("ITTX 教师回复（提交路径 " + attempt + "）");

            tx.executeWithoutResult(status ->
                    teacherService.reply(TEACHER_ID, List.of(Constants.ROLE_TEACHER, "ADMIN"), questionId, req));

            // 新连接复核：事件行已持久化（同事务提交证据）
            InteractionReply reply = replyMapper.selectList(Wrappers.<InteractionReply>lambdaQuery()
                    .eq(InteractionReply::getQuestionId, questionId)).get(0);
            String eventKey = "QUESTION_REPLY:" + reply.getId();
            assertTrue(exists("SELECT COUNT(*) FROM sys_event_outbox WHERE event_key=?", eventKey),
                    "业务提交后 Outbox 事件必须持久存在");
            SysEventOutbox row = outboxMapper.selectOne(Wrappers.<SysEventOutbox>lambdaQuery()
                    .eq(SysEventOutbox::getEventKey, eventKey));
            assertEquals(SysEventOutbox.STATUS_PENDING, row.getStatus());
            track(row.getId());

            // 手工驱动一轮真实 Worker poll（Mock 投递成功）→ SUCCESS
            // 注意：MySQL DATETIME 秒级精度，next_retry_time 截断到秒；立即 poll 的
            // le(now) 谓词可能差一个亚秒窗口 → 跨秒等待后轮询（消除 flaky）
            Thread.sleep(1100);
            org.mockito.Mockito.reset(notificationClient);
            when(notificationClient.publish(any()))
                    .thenReturn(R.ok(Map.of("id", "1", "eventKey", eventKey)));
            worker().poll();

            SysEventOutbox after = outboxMapper.selectById(row.getId());
            if (after.getStatus() == SysEventOutbox.STATUS_SUCCESS
                    && after.getWorkerId() != null && after.getWorkerId().endsWith("-" + myPid)) {
                verify(notificationClient).publish(any());
                lastError = null;
                break;
            }
            // 被生产 Worker 抢先领取/投递 → 换新事件重试
            lastError = new AssertionError("第 " + attempt + " 次尝试被外部 Worker 抢先（status="
                    + after.getStatus() + " worker=" + after.getWorkerId() + "），换新事件重试");
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    // ------------------------------------------------------------------
    // 3) eventKey 唯一约束：重复入队幂等；同键直接插入被数据库拒绝
    // ------------------------------------------------------------------
    @Test
    void duplicateEventKey_rejectedByUniqueConstraint() {
        String key = "ITTX:UNIQ:" + System.nanoTime();
        long id1 = outboxService.enqueue(key, "QA", "it", STUDENT_ID, "QUESTION_REPLY", "t", "c", null);
        track(id1);
        assertTrue(id1 > 0);
        long id2 = outboxService.enqueue(key, "QA", "it", STUDENT_ID, "QUESTION_REPLY", "t", "c", null);
        assertEquals(id1, id2, "同一 eventKey 重复入队必须幂等返回同一行");

        assertThrows(DuplicateKeyException.class, () -> outboxMapper.insert(rawRow(key)),
                "同 eventKey 直接插入必须被唯一索引拒绝");
    }

    // ------------------------------------------------------------------
    // 4) sys_notification 唯一约束：重复投递被数据库拒绝
    // ------------------------------------------------------------------
    @Test
    void notificationUnique_recipientEventKey_rejectsDuplicate() throws Exception {
        String key = "ITTX:NOTIF:" + System.nanoTime();
        String sql = "INSERT INTO sys_notification (id, recipient_id, event_key, type, title, content,"
                + " biz_type, biz_id, read_status, deleted, create_time, update_time)"
                + " VALUES (?,?,?,'QUESTION_REPLY','t','c','QA','it',0,0,NOW(),NOW())";
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, System.nanoTime());
                ps.setLong(2, STUDENT_ID);
                ps.setString(3, key);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, System.nanoTime());
                ps.setLong(2, STUDENT_ID);
                ps.setString(3, key);
                assertThrows(SQLIntegrityConstraintViolationException.class, ps::executeUpdate,
                        "同 (recipient_id,event_key) 二次插入必须被唯一约束拒绝");
            }
        } finally {
            exec("DELETE FROM sys_notification WHERE event_key=?", key);
        }
    }

    // ------------------------------------------------------------------
    // 5) 领取原子性：同一行两次条件领取仅一次成功（Worker.claim 同谓词）
    // ------------------------------------------------------------------
    @Test
    void claim_isAtomic_secondClaimAffectsZeroRows() {
        LocalDateTime now = LocalDateTime.now();
        SysEventOutbox upd = new SysEventOutbox();
        upd.setStatus(SysEventOutbox.STATUS_SENDING);
        upd.setWorkerId("it-worker-A");
        upd.setLeaseUntil(now.plusMinutes(SysEventOutbox.LEASE_MINUTES));
        upd.setAttempt(1);
        upd.setUpdateTime(now);

        // 生产 Worker 可能抢先领取（毫秒级窗口）→ 换新行重试，最多 5 次
        AssertionError last = null;
        for (int i = 0; i < 5; i++) {
            long id = insertOutbox("ITTX:CLAIM:" + System.nanoTime() + ":" + i,
                    SysEventOutbox.STATUS_PENDING, 0, LocalDateTime.now().minusSeconds(1), null, null);
            int first = outboxMapper.update(upd, Wrappers.<SysEventOutbox>lambdaUpdate()
                    .eq(SysEventOutbox::getId, id)
                    .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_PENDING)
                    .le(SysEventOutbox::getNextRetryTime, now));
            if (first != 1) {
                last = new AssertionError("第 " + i + " 次领取被外部 Worker 抢先");
                continue;
            }
            int second = outboxMapper.update(upd, Wrappers.<SysEventOutbox>lambdaUpdate()
                    .eq(SysEventOutbox::getId, id)
                    .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_PENDING)
                    .le(SysEventOutbox::getNextRetryTime, now));
            assertEquals(0, second, "第二个 Worker 并发领取必须影响 0 行（原子性）");
            return;
        }
        throw last;
    }

    // ------------------------------------------------------------------
    // 6) 租约：未到期不可接管；到期可接管；SUCCESS 永不重新领取
    // ------------------------------------------------------------------
    @Test
    void lease_notExpiredCannotTakeover_expiredCan_successNever() {
        LocalDateTime now = LocalDateTime.now();
        SysEventOutbox takeover = new SysEventOutbox();
        takeover.setStatus(SysEventOutbox.STATUS_SENDING);
        takeover.setWorkerId("worker-new");
        takeover.setLeaseUntil(now.plusMinutes(SysEventOutbox.LEASE_MINUTES));
        takeover.setAttempt(2);
        takeover.setUpdateTime(now);

        // (a) 未到期：接管条件更新影响 0 行（防止错误状态覆盖）
        long idA = insertOutbox("ITTX:LEASE-A:" + System.nanoTime(), SysEventOutbox.STATUS_SENDING, 1,
                now.minusSeconds(1), "worker-old", now.plusMinutes(3));
        assertEquals(0, outboxMapper.update(takeover, Wrappers.<SysEventOutbox>lambdaUpdate()
                .eq(SysEventOutbox::getId, idA)
                .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_SENDING)
                .lt(SysEventOutbox::getLeaseUntil, now)), "租约未到期不得接管");
        SysEventOutbox rowA = outboxMapper.selectById(idA);
        assertEquals("worker-old", rowA.getWorkerId(), "原所有者保持不变");
        assertEquals(1, rowA.getAttempt(), "attempt 不得被未到期接管改动");

        // (b) 已到期：接管成功，attempt+1（若被外部 Worker 抢先接管则换新行重试）
        AssertionError lastB = null;
        for (int i = 0; i < 5; i++) {
            long idB = insertOutbox("ITTX:LEASE-B:" + System.nanoTime() + ":" + i,
                    SysEventOutbox.STATUS_SENDING, 1, now.minusSeconds(1), "worker-old", now.minusSeconds(1));
            int r1 = outboxMapper.update(takeover, Wrappers.<SysEventOutbox>lambdaUpdate()
                    .eq(SysEventOutbox::getId, idB)
                    .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_SENDING)
                    .lt(SysEventOutbox::getLeaseUntil, now));
            if (r1 != 1) {
                lastB = new AssertionError("第 " + i + " 次到期接管被外部 Worker 抢先");
                continue;
            }
            assertEquals(2, outboxMapper.selectById(idB).getAttempt());
            lastB = null;
            break;
        }
        if (lastB != null) {
            throw lastB;
        }

        // (c) SUCCESS 行（即使带过期租约残留）永不被领取
        long idC = insertOutbox("ITTX:LEASE-C:" + System.nanoTime(), SysEventOutbox.STATUS_SUCCESS, 1,
                now.minusSeconds(1), "worker-old", now.minusSeconds(10));
        assertEquals(0, outboxMapper.update(takeover, Wrappers.<SysEventOutbox>lambdaUpdate()
                .eq(SysEventOutbox::getId, idC)
                .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_SENDING)
                .lt(SysEventOutbox::getLeaseUntil, now)), "SUCCESS 不得被重新领取");
    }

    // ------------------------------------------------------------------
    // 7) FAILED 保留 + 安全重试（人工重置 PENDING 后可再次投递）
    // ------------------------------------------------------------------
    @Test
    void failedRow_retained_notAutoPicked_manualResetRetries() {
        LocalDateTime now = LocalDateTime.now();
        String key = "ITTX:FAILED:" + System.nanoTime();
        long id = insertOutbox(key, SysEventOutbox.STATUS_FAILED, SysEventOutbox.MAX_ATTEMPT,
                now.plusMinutes(10), null, null);
        outboxMapper.update(null, Wrappers.<SysEventOutbox>lambdaUpdate()
                .eq(SysEventOutbox::getId, id)
                .set(SysEventOutbox::getError, "it: 模拟投递失败（超过 5 次）"));

        // Worker 候选查询（与 poll 相同谓词）不得包含 FAILED 行
        List<SysEventOutbox> candidates = outboxMapper.selectList(Wrappers.<SysEventOutbox>lambdaQuery()
                .and(w -> w
                        .and(w2 -> w2.eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_PENDING)
                                .le(SysEventOutbox::getNextRetryTime, now))
                        .or(w2 -> w2.eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_SENDING)
                                .isNotNull(SysEventOutbox::getLeaseUntil)
                                .lt(SysEventOutbox::getLeaseUntil, now)))
                .eq(SysEventOutbox::getId, id));
        assertTrue(candidates.isEmpty(), "FAILED 行不得被自动重试");

        // 安全重试运维路径：人工重置 PENDING → Worker 投递成功
        when(notificationClient.publish(any())).thenReturn(R.ok(Map.of("id", "1", "eventKey", key)));
        outboxMapper.update(null, Wrappers.<SysEventOutbox>lambdaUpdate()
                .eq(SysEventOutbox::getId, id)
                .set(SysEventOutbox::getStatus, SysEventOutbox.STATUS_PENDING)
                .set(SysEventOutbox::getAttempt, 0)
                .set(SysEventOutbox::getNextRetryTime, LocalDateTime.now().minusSeconds(1))
                .set(SysEventOutbox::getError, (Object) null));
        worker().poll();
        assertEquals(SysEventOutbox.STATUS_SUCCESS, outboxMapper.selectById(id).getStatus(),
                "人工重置后安全重试应成功");
    }

    // ------------------------------------------------------------------
    // fixtures & helpers
    // ------------------------------------------------------------------

    private NotificationOutboxWorker worker() {
        return new NotificationOutboxWorker(outboxMapper, notificationClient);
    }

    private long newQuestion() {
        InteractionQuestion q = new InteractionQuestion();
        q.setId(77744000L + (System.nanoTime() % 1_000_000));
        q.setTitle("ITTX 事务验收问题");
        q.setDescription("ITTX");
        q.setCourseId(IT_COURSE_ID);
        q.setUserId(STUDENT_ID);
        q.setAnswerTimes(0);
        q.setAnonymity(0);
        q.setHidden(0);
        q.setStatus(0);
        q.setCreateTime(LocalDateTime.now());
        q.setUpdateTime(LocalDateTime.now());
        questionMapper.insert(q);
        questionIds.add(q.getId());
        return q.getId();
    }

    private void track(long outboxId) {
        if (outboxId > 0 && !outboxIds.contains(outboxId)) {
            outboxIds.add(outboxId);
        }
    }

    private SysEventOutbox rawRow(String key) {
        SysEventOutbox row = new SysEventOutbox();
        row.setId(System.nanoTime());
        row.setEventKey(key);
        row.setBizType("QA");
        row.setBizId("it");
        row.setRecipientId(STUDENT_ID);
        row.setType("QUESTION_REPLY");
        row.setTitle("t");
        row.setContent("c");
        row.setStatus(SysEventOutbox.STATUS_PENDING);
        row.setAttempt(0);
        row.setNextRetryTime(LocalDateTime.now());
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        return row;
    }

    private long insertOutbox(String key, int status, int attempt, LocalDateTime nextRetry,
                              String workerId, LocalDateTime leaseUntil) {
        SysEventOutbox row = rawRow(key);
        row.setStatus(status);
        row.setAttempt(attempt);
        row.setNextRetryTime(nextRetry);
        row.setWorkerId(workerId);
        row.setLeaseUntil(leaseUntil);
        outboxMapper.insert(row);
        track(row.getId());
        return row.getId();
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

    private int count(String sql, Object... args) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
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
