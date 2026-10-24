package com.kzai.course.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.exception.BizException;
import com.kzai.course.dto.CourseQuery;
import com.kzai.course.entity.CourseChapter;
import com.kzai.course.mapper.CourseChapterMapper;
import com.kzai.course.vo.CourseDetailVO;
import com.kzai.course.vo.CourseVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 课程服务集成测试：H2（MySQL 兼容模式）真实执行 SQL，Redis 使用本地实例验证缓存键。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:course_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
@Transactional
class CourseServiceTest {
    @Autowired private com.kzai.course.controller.RecommendationSourceController recommendationSource;

    @Test
    void recommendationSourceExportsOnlyPublicFieldsAndTombstones() throws Exception {
        var rows = recommendationSource.page(0,100).getData();
        assertTrue(rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("visible"))));
        assertTrue(rows.stream().anyMatch(r -> Boolean.FALSE.equals(r.get("visible"))));
        long last=0;
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(var row:rows) {
            long id=Long.parseLong(String.valueOf(row.get("courseId")));assertTrue(id>last);last=id;
            if(Boolean.TRUE.equals(row.get("visible"))) {
                var data=json.valueToTree(row.get("course"));
                assertFalse(data.has("courseDetail"));assertFalse(data.has("videoUrl"));assertFalse(data.has("resources"));
                assertTrue(data.has("usePeople"));assertTrue(data.has("chapters"));
            } else assertEquals(java.util.Set.of("courseId","visible"),row.keySet());
        }
        assertTrue(recommendationSource.page(last,100).getData().isEmpty());
    }

    @Autowired
    private CourseService courseService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CourseChapterMapper chapterMapper;

    @Autowired
    private ChapterService chapterService;

    @BeforeEach
    void clearCache() {
        // 缓存键 course:{id}，设计说明书 8.1
        redisTemplate.delete("course:2001");
    }

    @Test
    void page_onlyOnlineCourses() {
        IPage<CourseVO> page = courseService.page(new CourseQuery());

        // seed：3 门已上架，另有下架(3)、草稿(1)、逻辑删除各 1 门
        assertEquals(3, page.getTotal());
        assertTrue(page.getRecords().stream().allMatch(c -> c.getStatus() == 2));
    }

    @Test
    void page_filterByCategory() {
        CourseQuery query = new CourseQuery();
        query.setCategoryId(202L);

        IPage<CourseVO> page = courseService.page(query);

        assertEquals(1, page.getTotal());
        assertEquals("大模型应用开发", page.getRecords().get(0).getName());
    }

    @Test
    void aiDetail_returnsRealChaptersInOrder() {
        // M15 REAL 防编造：AI 详情契约返回真实 course_chapter 标题与顺序（数据约束源）
        com.kzai.course.dto.CourseAiVO vo = courseService.aiDetail(2001L);
        assertNotNull(vo);
        assertEquals(2L, vo.getChapterCount());
        assertNotNull(vo.getChapters(), "chapters 不应为 null");
        assertEquals(2, vo.getChapters().size());
        assertEquals("第1章 演进", vo.getChapters().get(0).getName());
        assertEquals(1, vo.getChapters().get(0).getOrder());
        assertEquals("第2章 注册中心", vo.getChapters().get(1).getName());
        assertEquals(2, vo.getChapters().get(1).getOrder());
    }

    @Test
    void page_sortByPriceAsc() {
        CourseQuery query = new CourseQuery();
        query.setSort("price");

        List<Integer> prices = courseService.page(query).getRecords().stream()
                .map(CourseVO::getPrice).toList();

        assertEquals(List.of(0, 29900, 49900), prices);
    }

    @Test
    void page_keywordMatchesName() {
        CourseQuery query = new CourseQuery();
        query.setKeyword("微服务");

        assertEquals(1, courseService.page(query).getTotal());
    }

    @Test
    void search_matchesNameAndContent() {
        // 需求 3.2.3.1：按名称、简介或适用人群搜索（course_detail 详情长文本不参与检索）
        List<CourseVO> byName = courseService.search("大模型");
        List<CourseVO> byUsePeople = courseService.search("应用开发者");

        assertEquals(1, byName.size());
        assertEquals("大模型应用开发", byName.get(0).getName());
        assertEquals(1, byUsePeople.size());
        assertEquals("大模型应用开发", byUsePeople.get(0).getName());
    }

    @Test
    void detail_buildsContentAndChapters() {
        CourseDetailVO vo = courseService.detail(2001L);

        assertEquals("Java 微服务实战", vo.getName());
        assertEquals("微服务介绍", vo.getCourseIntroduce());
        assertEquals(new BigDecimal("299.00"), vo.getPriceYuan());
        assertEquals(new BigDecimal("4.7"), vo.getScoreStar());
        assertEquals(2, vo.getChapters().size());
        assertEquals(1, vo.getChapters().get(0).getChapterOrder());
        // M5：试看必须由教师显式设置（course_chapter_extra），不再硬编码"首章默认可试看"
        assertFalse(vo.getChapters().get(0).getTryWatch(), "未设置试看时首章不可试看");
        assertFalse(vo.getChapters().get(1).getTryWatch());
    }

    @Test
    void detail_tryWatchFollowsTeacherSetting() {
        // 教师显式把第二章设为可试看后，学员端目录才标记为可试看
        com.kzai.common.context.UserContext.set(1L, "admin",
                com.kzai.common.constant.Constants.USER_TYPE_STAFF,
                java.util.List.of(com.kzai.common.constant.Constants.ROLE_ADMIN), "test");
        Long chapterId = chapterMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<CourseChapter>lambdaQuery()
                                .eq(CourseChapter::getCourseId, 2001L)
                                .eq(CourseChapter::getChapterOrder, 2))
                .get(0).getId();
        chapterService.setPreview(chapterId, true, 600);

        CourseDetailVO vo = courseService.detail(2001L);

        assertFalse(vo.getChapters().get(0).getTryWatch(), "未设置的章节仍不可试看");
        assertTrue(vo.getChapters().get(1).getTryWatch(), "教师显式设置后才可试看");
        assertEquals(600, vo.getChapters().get(1).getVideoDurationSec());
    }

    @Test
    void detail_writesRedisCache() {
        courseService.detail(2001L);

        String cached = redisTemplate.opsForValue().get("course:2001");
        assertNotNull(cached, "设计说明书 8.1：课程热点缓存 course:{id}");
        assertTrue(cached.contains("Java 微服务实战"));
    }

    @Test
    void detail_notExists_throwsBizException() {
        assertThrows(BizException.class, () -> courseService.detail(999999L));
    }

    @Test
    void detail_logicallyDeleted_throwsBizException() {
        // 2006 已逻辑删除，@TableLogic 后应视为不存在
        assertThrows(BizException.class, () -> courseService.detail(2006L));
    }
}
