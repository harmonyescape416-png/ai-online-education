package com.kzai.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.BizException;
import com.kzai.course.dto.ChapterOrderRequest;
import com.kzai.course.dto.ChapterSaveRequest;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseChapter;
import com.kzai.course.mapper.CourseChapterMapper;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.mapper.CourseResourceMapper;
import com.kzai.course.vo.ChapterManageVO;
import com.kzai.course.vo.ResourceVO;
import com.kzai.course.entity.CourseChapterExtra;
import com.kzai.course.entity.CourseResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 章节管理（M3）：使用原始 course_chapter 表的 8 个字段，
 * 不做任何字段扩展；视频/资料通过 course_resource 关联表达。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterService {

    private final CourseChapterMapper chapterMapper;
    private final CourseMapper courseMapper;
    private final CourseResourceMapper resourceMapper;
    private final CourseSupport courseSupport;
    private final TeacherCourseService teacherCourseService;
    private final LearnOutlineService learnOutlineService;

    public List<ChapterManageVO> list(Long courseId) {
        courseSupport.requireOwned(courseId);
        List<CourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<CourseChapter>lambdaQuery()
                        .eq(CourseChapter::getCourseId, courseId)
                        .eq(CourseChapter::getDeleted, 0)
                        .orderByAsc(CourseChapter::getChapterOrder));
        Map<Long, List<ResourceVO>> byChapter = resourcesByChapter(courseId);
        Map<Long, CourseChapterExtra> extras = learnOutlineService.extrasOf(courseId);
        List<ChapterManageVO> vos = new ArrayList<>();
        for (CourseChapter chapter : chapters) {
            CourseChapterExtra extra = extras.get(chapter.getId());
            vos.add(ChapterManageVO.builder()
                    .id(chapter.getId())
                    .courseId(chapter.getCourseId())
                    .name(chapter.getName())
                    .chapterOrder(chapter.getChapterOrder())
                    .description(chapter.getDescription())
                    .freePreview(extra != null && Integer.valueOf(1).equals(extra.getFreePreview()))
                    .videoDurationSec(extra == null ? null : extra.getVideoDurationSec())
                    .resources(byChapter.getOrDefault(chapter.getId(), List.of()))
                    .createTime(chapter.getCreateTime())
                    .build());
        }
        return vos;
    }

    @Transactional
    public Long add(Long courseId, ChapterSaveRequest request) {
        Course course = courseSupport.requireOwned(courseId);
        checkEditable(course);

        CourseChapter chapter = new CourseChapter();
        chapter.setCourseId(courseId);
        chapter.setName(request.getName().trim());
        chapter.setDescription(request.getDescription());
        chapter.setChapterOrder(nextOrder(courseId));
        chapter.setDeleted(0);
        chapterMapper.insert(chapter);
        syncSectionNum(courseId);
        teacherCourseService.evictCache(courseId);
        return chapter.getId();
    }

    @Transactional
    public void update(Long chapterId, ChapterSaveRequest request) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BizException("章节不存在");
        }
        Course course = courseSupport.requireOwned(chapter.getCourseId());
        checkEditable(course);

        chapter.setName(request.getName().trim());
        chapter.setDescription(request.getDescription());
        chapterMapper.updateById(chapter);
        teacherCourseService.evictCache(course.getId());
    }

    /** 逻辑删除：保留数据，列表不再展示 */
    @Transactional
    public void delete(Long chapterId) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BizException("章节不存在");
        }
        Course course = courseSupport.requireOwned(chapter.getCourseId());
        checkEditable(course);

        // @TableLogic：deleteById 生成 UPDATE ... SET deleted=1，逻辑删除而非物理删除
        chapterMapper.deleteById(chapterId);
        syncSectionNum(course.getId());
        teacherCourseService.evictCache(course.getId());
    }

    /** 调整章节顺序：传入的 ID 顺序即最终顺序 */
    @Transactional
    public void reorder(Long courseId, ChapterOrderRequest request) {
        Course course = courseSupport.requireOwned(courseId);
        checkEditable(course);

        List<Long> ids = request.getChapterIds();
        List<CourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<CourseChapter>lambdaQuery()
                        .eq(CourseChapter::getCourseId, courseId)
                        .eq(CourseChapter::getDeleted, 0));
        Map<Long, CourseChapter> owned = new HashMap<>();
        for (CourseChapter chapter : chapters) {
            owned.put(chapter.getId(), chapter);
        }
        for (Long id : ids) {
            if (!owned.containsKey(id)) {
                throw new BizException("章节不属于该课程：" + id);
            }
        }
        int order = 1;
        for (Long id : ids) {
            CourseChapter chapter = owned.get(id);
            chapter.setChapterOrder(order++);
            chapterMapper.updateById(chapter);
        }
        teacherCourseService.evictCache(courseId);
    }

    private void checkEditable(Course course) {
        if (Integer.valueOf(4).equals(course.getStatus())) {
            throw new BizException("已完结课程不可维护章节");
        }
    }

    private int nextOrder(Long courseId) {
        List<CourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<CourseChapter>lambdaQuery().eq(CourseChapter::getCourseId, courseId));
        int max = 0;
        for (CourseChapter chapter : chapters) {
            if (chapter.getChapterOrder() != null && chapter.getChapterOrder() > max) {
                max = chapter.getChapterOrder();
            }
        }
        return max + 1;
    }

    /**
     * M5：设置章节是否可试看。
     * 试看标识落 course_chapter_extra（扩展表），原始 course_chapter 8 字段不变。
     * 教师只能设置本人课程的章节；免费课程无需试看标识但也不禁止设置。
     */
    @Transactional
    public void setPreview(Long chapterId, Boolean freePreview, Integer videoDurationSec) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BizException("章节不存在");
        }
        Course course = courseSupport.requireOwned(chapter.getCourseId());
        checkEditable(course);
        if (videoDurationSec != null && videoDurationSec < 0) {
            throw new BizException("视频时长不能为负数");
        }
        learnOutlineService.setPreview(chapterId, freePreview, videoDurationSec);
        teacherCourseService.evictCache(course.getId());
    }

    /** 章节数同步到 course.section_num（原始字段，未新增字段） */
    private void syncSectionNum(Long courseId) {
        Long count = chapterMapper.selectCount(Wrappers.<CourseChapter>lambdaQuery()
                .eq(CourseChapter::getCourseId, courseId)
                .eq(CourseChapter::getDeleted, 0));
        Course course = courseMapper.selectById(courseId);
        if (course != null) {
            course.setSectionNum(count == null ? 0 : count.intValue());
            courseMapper.updateById(course);
        }
    }

    private Map<Long, List<ResourceVO>> resourcesByChapter(Long courseId) {
        List<CourseResource> resources = resourceMapper.selectList(
                Wrappers.<CourseResource>lambdaQuery()
                        .eq(CourseResource::getCourseId, courseId)
                        .eq(CourseResource::getDeleted, 0));
        Map<Long, List<ResourceVO>> map = new HashMap<>();
        for (CourseResource resource : resources) {
            if (resource.getChapterId() != null) {
                map.computeIfAbsent(resource.getChapterId(), k -> new ArrayList<>())
                        .add(ResourceService.toVO(resource));
            }
        }
        return map;
    }

    /** 校验章节属于该课程（供资料绑定使用） */
    public void requireChapterOfCourse(Long chapterId, Long courseId) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())
                || !Objects.equals(chapter.getCourseId(), courseId)) {
            throw new BizException("章节不存在或不属于该课程");
        }
    }
}
