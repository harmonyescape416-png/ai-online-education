package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.client.TradeClient;
import com.kzai.learning.client.UserClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.dto.LearnChapterDTO;
import com.kzai.learning.dto.ProgressReportRequest;
import com.kzai.learning.entity.LearningLesson;
import com.kzai.learning.entity.LearningLessonProgress;
import com.kzai.learning.mapper.LearningLessonMapper;
import com.kzai.learning.mapper.LearningLessonProgressMapper;
import com.kzai.learning.vo.LessonDetailVO;
import com.kzai.learning.vo.LessonVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 学习服务 <b>真实 MySQL</b> 集成测试（M6 验收项 9）。
 * <p>
 * 默认不执行（需显式 `KZAI_MYSQL_TEST=true`），因为 `mvn test` 的其余用例跑在 H2 上。
 * 本类<b>不覆盖</b>数据源：直接使用 application.yml 中配置的
 * `jdbc:mysql://127.0.0.1:3306/course_stack_ai`，并把实际 JDBC URL 打印到测试输出作为证据。
 * 课程/交易服务仍按内部契约用桩提供真实形态数据，学习业务本身全部走真实 Service + Mapper + MySQL。
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false"})
@org.springframework.transaction.annotation.Transactional
@EnabledIfEnvironmentVariable(named = "KZAI_MYSQL_TEST", matches = "true")
class LearningMysqlIntegrationTest {

    private static final long USER_A = 7770001L;
    private static final long FREE_COURSE = 9900001L;

    @Autowired
    private LearningService learningService;
    @Autowired
    private LearningLessonMapper lessonMapper;
    @Autowired
    private LearningLessonProgressMapper progressMapper;
    @Autowired
    private DataSource dataSource;

    @MockBean
    private CourseClient courseClient;
    @MockBean
    private TradeClient tradeClient;
    @MockBean
    private UserClient userClient;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            System.out.println("[MySQL-IT] jdbc.url = " + c.getMetaData().getURL());
            System.out.println("[MySQL-IT] jdbc.user = " + c.getMetaData().getUserName());
            System.out.println("[MySQL-IT] product   = " + c.getMetaData().getDatabaseProductName()
                    + " " + c.getMetaData().getDatabaseProductVersion());
        }
        UserContext.set(USER_A, "mysql-it", Constants.USER_TYPE_STUDENT,
                List.of(Constants.ROLE_STUDENT), "test");
        when(courseClient.learnOutline(anyLong())).thenReturn(R.ok(freeOutline()));
    }

    @AfterEach
    void tearDown() {
        progressMapper.delete(Wrappers.<LearningLessonProgress>lambdaQuery()
                .eq(LearningLessonProgress::getUserId, USER_A));
        lessonMapper.delete(Wrappers.<LearningLesson>lambdaQuery()
                .eq(LearningLesson::getUserId, USER_A));
        UserContext.clear();
    }

    @Test
    void mysql_realInsertAndProgress() {
        LessonVO vo = learningService.enroll(USER_A, FREE_COURSE);
        assertNotNull(vo.getId());

        // 直连同一 MySQL 复核：不是内存库
        LearningLesson row = lessonMapper.selectOne(Wrappers.<LearningLesson>lambdaQuery()
                .eq(LearningLesson::getUserId, USER_A)
                .eq(LearningLesson::getCourseId, FREE_COURSE));
        assertNotNull(row, "learning_lesson 必须真实落库");
        assertEquals(vo.getId(), row.getId());

        Long chapterId = 99000011L;
        ProgressReportRequest r1 = new ProgressReportRequest();
        r1.setChapterId(chapterId);
        r1.setVideoPositionSec(300);
        r1.setVideoDurationSec(1200);
        learningService.reportProgress(USER_A, FREE_COURSE, r1);

        LearningLessonProgress p = progressMapper.selectOne(Wrappers.<LearningLessonProgress>lambdaQuery()
                .eq(LearningLessonProgress::getUserId, USER_A)
                .eq(LearningLessonProgress::getChapterId, chapterId));
        assertNotNull(p);
        assertEquals(300, p.getVideoPositionSec());

        // 拖动跳跃：位置前进但有效时长不得虚增
        progressMapper.update(null, Wrappers.<LearningLessonProgress>lambdaUpdate()
                .set(LearningLessonProgress::getUpdateTime, LocalDateTime.now().minusSeconds(5))
                .eq(LearningLessonProgress::getId, p.getId()));
        ProgressReportRequest r2 = new ProgressReportRequest();
        r2.setChapterId(chapterId);
        r2.setVideoPositionSec(1100);
        r2.setVideoDurationSec(1200);
        LessonDetailVO after = learningService.reportProgress(USER_A, FREE_COURSE, r2);

        assertEquals(1100, after.getLastVideoPositionSec());
        assertTrue(after.getStudySeconds() <= 300 + 8,
                "MySQL 真实库上同样不得把拖动跳跃计入有效时长，实际=" + after.getStudySeconds());
    }

    @Test
    void mysql_courseProgressSyncedToLearningLesson() {
        learningService.enroll(USER_A, FREE_COURSE);
        learningService.completeChapter(USER_A, FREE_COURSE, 99000011L);
        learningService.completeChapter(USER_A, FREE_COURSE, 99000012L);

        LessonDetailVO detail = learningService.detail(USER_A, FREE_COURSE);
        LearningLesson row = lessonMapper.selectOne(Wrappers.<LearningLesson>lambdaQuery()
                .eq(LearningLesson::getUserId, USER_A)
                .eq(LearningLesson::getCourseId, FREE_COURSE));

        assertEquals(66, detail.getProgress());
        assertEquals(detail.getProgress(), row.getProgress(),
                "接口进度与 learning_lesson.progress 必须同刻一致");
    }

    private CourseOutlineDTO freeOutline() {
        CourseOutlineDTO outline = new CourseOutlineDTO();
        outline.setId(FREE_COURSE);
        outline.setName("MySQL集成测试免费课");
        outline.setFree(1);
        outline.setPrice(0);
        outline.setStatus(Constants.COURSE_STATUS_ONLINE);
        outline.setCreater(7L);
        outline.setCategoryEnabled(true);
        outline.setStudentVisible(true);
        outline.setChapters(List.of(
                chapter(99000011L, 1), chapter(99000012L, 2), chapter(99000013L, 3)));
        return outline;
    }

    private LearnChapterDTO chapter(Long id, int order) {
        LearnChapterDTO c = new LearnChapterDTO();
        c.setId(id);
        c.setName("第" + order + "章");
        c.setChapterOrder(order);
        return c;
    }
}
