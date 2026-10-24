package com.kzai.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.course.dto.ChapterOrderRequest;
import com.kzai.course.dto.ChapterSaveRequest;
import com.kzai.course.dto.CourseSaveRequest;
import com.kzai.course.dto.TeacherCourseQuery;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseAudit;
import com.kzai.course.entity.CourseChapter;
import com.kzai.course.mapper.CourseAuditMapper;
import com.kzai.course.mapper.CourseChapterMapper;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.vo.ChapterManageVO;
import com.kzai.course.vo.TeacherCourseDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 课程管理集成测试：H2（MySQL 兼容模式）真实执行 SQL，覆盖分类、教师课程、
 * 章节、审核流转与文件上传校验。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:course_manage_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
@Transactional
class CourseManageServiceTest {

    @Autowired
    private TeacherCourseService teacherCourseService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private AuditService auditService;
    @Autowired
    private CourseService courseService;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private CourseMapper courseMapper;
    @Autowired
    private CourseChapterMapper chapterMapper;
    @Autowired
    private CourseAuditMapper auditMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void asTeacher() {
        UserContext.set(2L, "teacher01", Constants.USER_TYPE_TEACHER, List.of(Constants.ROLE_TEACHER), "test");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void createCourse_savedAsPendingAndPriceInCents() {
        Long id = teacherCourseService.create(buildRequest("M3 测试课程", 19900));

        Course course = courseMapper.selectById(id);
        // 新建课程保存为待上架，价格以分存储
        assertEquals(Constants.COURSE_STATUS_PENDING, course.getStatus());
        assertEquals(19900, course.getPrice());
        assertEquals(0, course.getFree());
        assertEquals(2L, course.getCreater());

        TeacherCourseDetailVO detail = teacherCourseService.detail(id);
        assertEquals("零基础", detail.getUsePeople());
        assertEquals("课程简介", detail.getCourseIntroduce());
        assertEquals("课程详情", detail.getCourseDetail());
    }

    @Test
    void updateOtherTeachersCourse_forbidden() {
        // 切换到另一个教师账号（非课程 2005 的创建人）
        UserContext.set(99L, "teacherB", Constants.USER_TYPE_TEACHER, List.of(Constants.ROLE_TEACHER), "test");

        AuthException ex = assertThrows(AuthException.class,
                () -> teacherCourseService.update(2005L, buildRequest("越权改名", 100)));
        assertEquals(403, ex.getCode());
        // 归属校验生效：课程未被篡改
        assertEquals("草稿课程", courseMapper.selectById(2005L).getName());
    }

    @Test
    void submitThenApprove_makesCourseVisibleToStudent() {
        teacherCourseService.submit(2005L);
        CourseAudit pending = auditService.latestAudit(2005L);
        assertEquals(Constants.AUDIT_PENDING, pending.getAuditStatus());

        // 提交后教师不可再编辑
        assertThrows(BizException.class, () -> teacherCourseService.update(2005L, buildRequest("改名", 100)));

        // 学员访问未上架课程
        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
        assertThrows(BizException.class, () -> courseService.detail(2005L));

        // 管理员审核通过
        UserContext.set(1L, "admin", Constants.USER_TYPE_STAFF, List.of(Constants.ROLE_ADMIN), "test");
        auditService.approve(2005L);
        assertEquals(Constants.COURSE_STATUS_ONLINE, courseMapper.selectById(2005L).getStatus());

        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
        assertNotNull(courseService.detail(2005L));
    }

    @Test
    void rejectKeepsCourseInvisibleAndReasonVisible() {
        teacherCourseService.submit(2005L);
        UserContext.set(1L, "admin", Constants.USER_TYPE_STAFF, List.of(Constants.ROLE_ADMIN), "test");
        auditService.reject(2005L, "封面不清晰");

        assertEquals(Constants.COURSE_STATUS_PENDING, courseMapper.selectById(2005L).getStatus());
        CourseAudit rejected = auditService.latestAudit(2005L);
        assertEquals(Constants.AUDIT_REJECTED, rejected.getAuditStatus());
        assertEquals("封面不清晰", rejected.getReason());

        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
        assertThrows(BizException.class, () -> courseService.detail(2005L));
    }

    @Test
    void offlineCourseHiddenFromStudents() {
        UserContext.set(1L, "admin", Constants.USER_TYPE_STAFF, List.of(Constants.ROLE_ADMIN), "test");
        auditService.offline(2001L, "内容违规");

        assertEquals(Constants.COURSE_STATUS_OFFLINE, courseMapper.selectById(2001L).getStatus());
        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
        assertThrows(BizException.class, () -> courseService.detail(2001L));
    }

    @Test
    void chapter_addReorderAndLogicalDelete() {
        Long first = chapterService.add(2001L, chapter("第3章"));
        Long second = chapterService.add(2001L, chapter("第4章"));

        ChapterOrderRequest order = new ChapterOrderRequest();
        order.setChapterIds(List.of(second, first, 3001L, 3002L));
        chapterService.reorder(2001L, order);

        assertEquals(1, chapterMapper.selectById(second).getChapterOrder());
        assertEquals(2, chapterMapper.selectById(first).getChapterOrder());

        chapterService.delete(second);
        // 逻辑删除：记录仍在库中，deleted=1（MP @TableLogic 会过滤，故用原生 SQL 校验）
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM course_chapter WHERE id = ?", Integer.class, second);
        assertEquals(1, deletedFlag);

        List<ChapterManageVO> visible = chapterService.list(2001L);
        assertTrue(visible.stream().noneMatch(c -> c.getId().equals(second)));
        // 章节数同步：原有 2 章 + 新增 2 章 - 删除 1 章 = 3
        assertEquals(3, courseMapper.selectById(2001L).getSectionNum());
    }

    @Test
    void categoryWithCoursesCannotBeDeleted() {
        // 201（Java）无子分类，但被课程 2001/2004/2005 引用
        BizException ex = assertThrows(BizException.class, () -> categoryService.delete(201L));
        assertTrue(ex.getMessage().contains("门课程"), ex.getMessage());
    }

    @Test
    void categoryWithChildrenCannotBeDeleted() {
        BizException ex = assertThrows(BizException.class, () -> categoryService.delete(101L));
        assertTrue(ex.getMessage().contains("子分类"), ex.getMessage());
    }

    @Test
    void threeLevelCategoryRejected() {
        // 201 是二级分类，不能再挂子分类（保留三级字段语义，但本阶段不开放创建）
        var request = new com.kzai.course.dto.CategorySaveRequest();
        request.setName("三级分类");
        request.setParentId(201L);
        assertThrows(BizException.class, () -> categoryService.create(request));
    }

    @Test
    void disabledCategoryHidesCoursesFromStudents() {
        UserContext.set(1L, "admin", Constants.USER_TYPE_STAFF, List.of(Constants.ROLE_ADMIN), "test");
        categoryService.updateStatus(101L, 0);

        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
        var page = courseService.page(new com.kzai.course.dto.CourseQuery());
        assertTrue(page.getRecords().stream().noneMatch(c -> c.getId() == 2001L));
        assertTrue(page.getRecords().stream().noneMatch(c -> c.getId() == 2002L));
    }

    @Test
    void uploadIllegalTypeAndOversizeRejected() {
        MockMultipartFile exe = new MockMultipartFile("file", "virus.exe",
                "application/octet-stream", "MZ".getBytes());
        assertThrows(BizException.class, () -> fileStorageService.store(exe));

        MockMultipartFile big = new MockMultipartFile("file", "big.png",
                "image/png", new byte[21 * 1024 * 1024]);
        assertThrows(BizException.class, () -> fileStorageService.store(big));

        MockMultipartFile ok = new MockMultipartFile("file", "cover.png",
                "image/png", new byte[]{1, 2, 3});
        assertTrue(fileStorageService.store(ok).endsWith(".png"));
    }

    @Test
    void teacherListOnlyOwnCourses() {
        var query = new TeacherCourseQuery();
        var page = teacherCourseService.page(query);
        // seed 中课程 creater 均为 2，当前教师即 2
        assertTrue(page.getTotal() >= 4);
        assertTrue(page.getRecords().stream().allMatch(c -> c.getPrice() != null));

        UserContext.set(99L, "teacherB", Constants.USER_TYPE_TEACHER, List.of(Constants.ROLE_TEACHER), "test");
        assertEquals(0, teacherCourseService.page(query).getTotal());
    }

    private CourseSaveRequest buildRequest(String name, int price) {
        CourseSaveRequest request = new CourseSaveRequest();
        request.setName(name);
        request.setFirstCateId(101L);
        request.setSecondCateId(201L);
        request.setCourseType(2);
        request.setPrice(price);
        request.setValidDuration(6);
        request.setUsePeople("零基础");
        request.setCourseIntroduce("课程简介");
        request.setCourseDetail("课程详情");
        return request;
    }

    private ChapterSaveRequest chapter(String name) {
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setName(name);
        request.setDescription(name + "描述");
        return request;
    }

    @SuppressWarnings("unused")
    private long countAudit(Long courseId) {
        return auditMapper.selectCount(Wrappers.<CourseAudit>lambdaQuery()
                .eq(CourseAudit::getCourseId, courseId));
    }
}
