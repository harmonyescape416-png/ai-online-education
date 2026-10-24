package com.kzai.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.BizException;
import com.kzai.course.dto.LearnChapterDTO;
import com.kzai.course.dto.LearnOutlineDTO;
import com.kzai.course.dto.LearnResourceDTO;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseChapter;
import com.kzai.course.entity.CourseChapterExtra;
import com.kzai.course.entity.CourseContent;
import com.kzai.course.entity.CourseResource;
import com.kzai.course.mapper.CourseChapterExtraMapper;
import com.kzai.course.mapper.CourseChapterMapper;
import com.kzai.course.mapper.CourseContentMapper;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.mapper.CourseResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 学习大纲（M5 内部契约）：为学习服务提供课程 + 章节 + 资源的只读视图。
 * <p>
 * 课程数据仍由课程服务维护，学习服务不复制课程业务数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearnOutlineService {

    private final CourseMapper courseMapper;
    private final CourseContentMapper contentMapper;
    private final CourseChapterMapper chapterMapper;
    private final CourseChapterExtraMapper chapterExtraMapper;
    private final CourseResourceMapper resourceMapper;
    private final CategoryService categoryService;

    /** 课程学习大纲；课程不存在返回 null */
    public LearnOutlineDTO outline(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || Integer.valueOf(1).equals(course.getDeleted())) {
            return null;
        }
        boolean categoryEnabled = categoryService.categoryEnabled(course.getFirstCateId())
                && categoryService.categoryEnabled(course.getSecondCateId());
        boolean studentVisible = Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())
                && categoryEnabled;

        List<CourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<CourseChapter>lambdaQuery()
                        .eq(CourseChapter::getCourseId, courseId)
                        .eq(CourseChapter::getDeleted, 0)
                        .orderByAsc(CourseChapter::getChapterOrder));
        Map<Long, CourseChapterExtra> extras = extrasOf(courseId);

        List<LearnChapterDTO> chapterDTOs = new ArrayList<>();
        for (CourseChapter chapter : chapters) {
            CourseChapterExtra extra = extras.get(chapter.getId());
            LearnChapterDTO dto = new LearnChapterDTO();
            dto.setId(chapter.getId());
            dto.setCourseId(chapter.getCourseId());
            dto.setName(chapter.getName());
            dto.setChapterOrder(chapter.getChapterOrder());
            dto.setDescription(chapter.getDescription());
            // 试看来自教师显式设置，不做"第一章永远免费"的硬编码推断
            dto.setFreePreview(extra != null && Integer.valueOf(1).equals(extra.getFreePreview()));
            dto.setVideoDurationSec(extra == null ? null : extra.getVideoDurationSec());
            chapterDTOs.add(dto);
        }

        List<CourseResource> resources = resourceMapper.selectList(
                Wrappers.<CourseResource>lambdaQuery()
                        .eq(CourseResource::getCourseId, courseId)
                        .eq(CourseResource::getDeleted, 0)
                        .orderByAsc(CourseResource::getCreateTime));
        List<LearnResourceDTO> resourceDTOs = new ArrayList<>();
        for (CourseResource resource : resources) {
            LearnResourceDTO dto = new LearnResourceDTO();
            dto.setId(resource.getId());
            dto.setCourseId(resource.getCourseId());
            dto.setChapterId(resource.getChapterId());
            dto.setKind(resource.getKind());
            dto.setFileName(resource.getFileName());
            dto.setFileUrl(resource.getFileUrl());
            dto.setFileSize(resource.getFileSize());
            dto.setContentType(resource.getContentType());
            resourceDTOs.add(dto);
        }

        CourseContent content = contentMapper.selectById(courseId);
        LearnOutlineDTO outline = new LearnOutlineDTO();
        outline.setId(course.getId());
        outline.setName(course.getName());
        outline.setCoverUrl(course.getCoverUrl());
        outline.setFree(course.getFree());
        outline.setPrice(course.getPrice());
        outline.setStatus(course.getStatus());
        outline.setValidDuration(course.getValidDuration());
        outline.setCreater(course.getCreater());
        outline.setIntroduce(content == null ? null : content.getCourseIntroduce());
        outline.setCategoryEnabled(categoryEnabled);
        outline.setStudentVisible(studentVisible);
        outline.setChapters(chapterDTOs);
        outline.setResources(resourceDTOs);
        return outline;
    }

    /** 教师名下课程（学情统计筛选）：返回精简大纲，不含章节/资源 */
    public List<LearnOutlineDTO> byCreator(Long creatorId) {
        if (creatorId == null) {
            return List.of();
        }
        List<Course> courses = courseMapper.selectList(
                Wrappers.<Course>lambdaQuery()
                        .eq(Course::getCreater, creatorId)
                        .eq(Course::getDeleted, 0)
                        .orderByDesc(Course::getCreateTime));
        List<LearnOutlineDTO> result = new ArrayList<>();
        for (Course course : courses) {
            LearnOutlineDTO dto = new LearnOutlineDTO();
            dto.setId(course.getId());
            dto.setName(course.getName());
            dto.setCoverUrl(course.getCoverUrl());
            dto.setFree(course.getFree());
            dto.setPrice(course.getPrice());
            dto.setStatus(course.getStatus());
            dto.setCreater(course.getCreater());
            result.add(dto);
        }
        return result;
    }

    /** 课程归属判定：courseId → 是否由该用户创建 */
    public Map<Long, Boolean> ownership(Long userId, List<Long> courseIds) {
        Map<Long, Boolean> result = new HashMap<>();
        if (userId == null || courseIds == null) {
            return result;
        }
        for (Long courseId : courseIds) {
            if (courseId == null) {
                continue;
            }
            Course course = courseMapper.selectById(courseId);
            result.put(courseId, course != null && Objects.equals(course.getCreater(), userId));
        }
        return result;
    }

    /** 章节扩展属性（试看 / 视频时长） */
    public Map<Long, CourseChapterExtra> extrasOf(Long courseId) {
        List<CourseChapterExtra> extras = chapterExtraMapper.selectList(
                Wrappers.<CourseChapterExtra>lambdaQuery()
                        .eq(CourseChapterExtra::getCourseId, courseId));
        return extras.stream().collect(Collectors.toMap(
                CourseChapterExtra::getChapterId, e -> e, (a, b) -> a));
    }

    /** 单章扩展属性 */
    public CourseChapterExtra extraOf(Long chapterId) {
        List<CourseChapterExtra> extras = chapterExtraMapper.selectList(
                Wrappers.<CourseChapterExtra>lambdaQuery()
                        .eq(CourseChapterExtra::getChapterId, chapterId));
        return extras.isEmpty() ? null : extras.get(0);
    }

    /** 设置章节试看：教师显式设置，结果为未购学员可访问的唯一依据 */
    public void setPreview(Long chapterId, Boolean freePreview, Integer videoDurationSec) {
        CourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BizException("章节不存在");
        }
        CourseChapterExtra extra = extraOf(chapterId);
        if (extra == null) {
            extra = new CourseChapterExtra();
            extra.setCourseId(chapter.getCourseId());
            extra.setChapterId(chapterId);
            extra.setFreePreview(Boolean.TRUE.equals(freePreview) ? 1 : 0);
            extra.setVideoDurationSec(videoDurationSec);
            chapterExtraMapper.insert(extra);
            return;
        }
        extra.setFreePreview(Boolean.TRUE.equals(freePreview) ? 1 : 0);
        if (videoDurationSec != null) {
            extra.setVideoDurationSec(videoDurationSec);
        }
        chapterExtraMapper.updateById(extra);
    }

    /** 供章节列表展示：按课程取扩展属性 */
    public Map<Long, CourseChapterExtra> extrasByCourse(Long courseId) {
        return extrasOf(courseId);
    }

    /** 章节排序工具（按 chapter_order 升序） */
    public static Comparator<CourseChapter> orderComparator() {
        return Comparator.comparing(c -> c.getChapterOrder() == null ? 0 : c.getChapterOrder());
    }
}
