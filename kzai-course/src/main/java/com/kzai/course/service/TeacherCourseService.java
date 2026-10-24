package com.kzai.course.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.util.MoneyUtils;
import com.kzai.course.dto.CourseSaveRequest;
import com.kzai.course.dto.TeacherCourseQuery;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseAudit;
import com.kzai.course.entity.CourseContent;
import com.kzai.course.mapper.CourseAuditMapper;
import com.kzai.course.mapper.CourseContentMapper;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.vo.TeacherCourseDetailVO;
import com.kzai.course.vo.TeacherCourseVO;
import com.kzai.course.vo.ResourceVO;
import com.kzai.course.vo.ChapterManageVO;
import com.kzai.course.entity.CourseChapter;
import com.kzai.course.entity.CourseResource;
import com.kzai.course.mapper.CourseChapterMapper;
import com.kzai.course.mapper.CourseResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 教师课程管理（M3）：列表、创建、编辑、详情、提交审核。
 * <p>
 * 约束：课程归属教师（course.creater），教师只能操作本人课程；
 * 价格以分存储；状态流转受审核流程约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherCourseService {

    private final CourseMapper courseMapper;
    private final CourseContentMapper contentMapper;
    private final CourseChapterMapper chapterMapper;
    private final CourseResourceMapper resourceMapper;
    private final CourseAuditMapper auditMapper;
    private final CategoryService categoryService;
    private final CourseSupport courseSupport;
    private final StringRedisTemplate redisTemplate;

    public IPage<TeacherCourseVO> page(TeacherCourseQuery query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 10 : query.getPageSize();

        var wrapper = Wrappers.<Course>lambdaQuery().eq(Course::getDeleted, 0);
        // 教师只看自己的课程；管理员看全部
        if (!CourseSupport.isBackstageAdmin()) {
            wrapper.eq(Course::getCreater, UserContext.getUserId());
        }
        if (StringUtils.hasText(query.getName())) {
            wrapper.like(Course::getName, query.getName().trim());
        }
        if (query.getStatus() != null) {
            wrapper.eq(Course::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(Course::getCreateTime);

        Page<Course> page = courseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return page.convert(this::toListVO);
    }

    @Transactional
    public Long create(CourseSaveRequest request) {
        validateCategories(request.getFirstCateId(), request.getSecondCateId());

        Course course = new Course();
        course.setName(request.getName().trim());
        course.setCourseType(request.getCourseType() == null ? 2 : request.getCourseType());
        course.setCoverUrl(request.getCoverUrl());
        course.setFirstCateId(request.getFirstCateId());
        course.setSecondCateId(request.getSecondCateId());
        int price = resolvePrice(request);
        course.setPrice(price);
        // 0 元即免费，保持原文 free 字段语义（0付费，1免费）
        course.setFree(price == 0 ? 1 : 0);
        course.setValidDuration(request.getValidDuration());
        // 新建课程保存为「待上架」，需提交审核后才可上架
        course.setStatus(Constants.COURSE_STATUS_PENDING);
        course.setSectionNum(0);
        course.setCreater(UserContext.getUserId());
        course.setUpdater(UserContext.getUserId());
        course.setDeleted(0);
        courseMapper.insert(course);

        saveContent(course.getId(), request, true);
        return course.getId();
    }

    @Transactional
    public void update(Long courseId, CourseSaveRequest request) {
        Course course = courseSupport.requireOwned(courseId);
        if (Integer.valueOf(Constants.COURSE_STATUS_FINISHED).equals(course.getStatus())) {
            throw new BizException("已完结课程不可编辑");
        }
        CourseAudit latest = latestAudit(courseId);
        if (latest != null && Integer.valueOf(Constants.AUDIT_PENDING).equals(latest.getAuditStatus())) {
            throw new BizException("课程处于待审核状态，审核完成前不可编辑");
        }
        validateCategories(request.getFirstCateId(), request.getSecondCateId());

        int resolvedPrice = resolvePrice(request);
        boolean keyFieldChanged = !Objects.equals(course.getName(), request.getName().trim())
                || !Objects.equals(course.getPrice(), resolvedPrice);

        course.setName(request.getName().trim());
        course.setCourseType(request.getCourseType() == null ? course.getCourseType() : request.getCourseType());
        if (request.getCoverUrl() != null) {
            course.setCoverUrl(request.getCoverUrl());
        }
        course.setFirstCateId(request.getFirstCateId());
        course.setSecondCateId(request.getSecondCateId());
        int price = resolvePrice(request);
        course.setPrice(price);
        course.setFree(price == 0 ? 1 : 0);
        if (request.getValidDuration() != null) {
            course.setValidDuration(request.getValidDuration());
        }
        // 已上架课程修改关键字段（名称/价格）后需重新审核：回到待上架，学员端暂时不可见
        if (keyFieldChanged && Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())) {
            CourseSupport.checkTransition(Constants.COURSE_STATUS_ONLINE, Constants.COURSE_STATUS_PENDING);
            course.setStatus(Constants.COURSE_STATUS_PENDING);
        }
        course.setUpdater(UserContext.getUserId());
        courseMapper.updateById(course);

        saveContent(courseId, request, false);
        evictCache(courseId);
    }

    public TeacherCourseDetailVO detail(Long courseId) {
        Course course = courseSupport.requireOwned(courseId);
        CourseContent content = contentMapper.selectById(courseId);
        CourseAudit latest = latestAudit(courseId);

        List<CourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<CourseChapter>lambdaQuery()
                        .eq(CourseChapter::getCourseId, courseId)
                        .eq(CourseChapter::getDeleted, 0)
                        .orderByAsc(CourseChapter::getChapterOrder));
        List<CourseResource> resources = resourceMapper.selectList(
                Wrappers.<CourseResource>lambdaQuery()
                        .eq(CourseResource::getCourseId, courseId)
                        .eq(CourseResource::getDeleted, 0)
                        .orderByAsc(CourseResource::getCreateTime));
        Map<Long, List<ResourceVO>> byChapter = new HashMap<>();
        for (CourseResource resource : resources) {
            if (resource.getChapterId() != null) {
                byChapter.computeIfAbsent(resource.getChapterId(), k -> new ArrayList<>())
                        .add(ResourceService.toVO(resource));
            }
        }

        boolean pending = latest != null
                && Integer.valueOf(Constants.AUDIT_PENDING).equals(latest.getAuditStatus());

        return TeacherCourseDetailVO.builder()
                .id(course.getId())
                .name(course.getName())
                .coverUrl(course.getCoverUrl())
                .courseType(course.getCourseType())
                .firstCateId(course.getFirstCateId())
                .secondCateId(course.getSecondCateId())
                .categoryName(categoryService.categoryName(course.getFirstCateId(), course.getSecondCateId()))
                .free(course.getFree())
                .price(course.getPrice())
                .priceYuan(toYuan(course.getPrice()))
                .status(course.getStatus())
                .statusText(CourseSupport.statusText(course.getStatus()))
                .sectionNum(course.getSectionNum())
                .validDuration(course.getValidDuration())
                .usePeople(content == null ? null : content.getUsePeople())
                .courseIntroduce(content == null ? null : content.getCourseIntroduce())
                .courseDetail(content == null ? null : content.getCourseDetail())
                .auditStatus(latest == null ? null : latest.getAuditStatus())
                .auditStatusText(CourseSupport.auditStatusText(latest == null ? null : latest.getAuditStatus()))
                .auditReason(latest == null ? null : latest.getReason())
                .auditorName(latest == null ? null : latest.getAuditorName())
                .auditTime(latest == null ? null : latest.getCreateTime())
                .editable(!pending)
                .chapters(chapters.stream().map(c -> ChapterManageVO.builder()
                        .id(c.getId())
                        .courseId(c.getCourseId())
                        .name(c.getName())
                        .chapterOrder(c.getChapterOrder())
                        .description(c.getDescription())
                        .resources(byChapter.getOrDefault(c.getId(), List.of()))
                        .createTime(c.getCreateTime())
                        .build()).toList())
                .resources(resources.stream().map(ResourceService::toVO).toList())
                .createTime(course.getCreateTime())
                .build();
    }

    /** 提交审核：仅待上架课程可提交，且不可重复提交 */
    @Transactional
    public void submit(Long courseId) {
        Course course = courseSupport.requireOwned(courseId);
        if (!Integer.valueOf(Constants.COURSE_STATUS_PENDING).equals(course.getStatus())) {
            throw new BizException("仅待上架课程可提交审核，当前状态：" + CourseSupport.statusText(course.getStatus()));
        }
        CourseAudit latest = latestAudit(courseId);
        if (latest != null && Integer.valueOf(Constants.AUDIT_PENDING).equals(latest.getAuditStatus())) {
            throw new BizException("课程已在审核中，请勿重复提交");
        }

        CourseAudit audit = new CourseAudit();
        audit.setCourseId(courseId);
        audit.setAuditStatus(Constants.AUDIT_PENDING);
        auditMapper.insert(audit);
        evictCache(courseId);
    }

    public CourseAudit latestAudit(Long courseId) {
        // create_time 只到秒，同一秒内可能有多条记录，故以 id 作为次级排序保证取到最新一条
        List<CourseAudit> audits = auditMapper.selectList(
                Wrappers.<CourseAudit>lambdaQuery()
                        .eq(CourseAudit::getCourseId, courseId)
                        .orderByDesc(CourseAudit::getCreateTime)
                        .orderByDesc(CourseAudit::getId));
        return audits.isEmpty() ? null : audits.get(0);
    }

    /** 课程最新审核状态（教师查看审核结果与意见） */
    public TeacherCourseVO auditStatus(Long courseId) {
        Course course = courseSupport.requireOwned(courseId);
        CourseAudit latest = latestAudit(courseId);
        TeacherCourseVO vo = toListVO(course);
        vo.setAuditReason(latest == null ? null : latest.getReason());
        return vo;
    }

    public void evictCache(Long courseId) {
        try {
            redisTemplate.delete(Constants.REDIS_KEY_COURSE + courseId);
        } catch (Exception e) {
            log.warn("[course] cache evict degraded: {}", e.getMessage());
        }
    }

    private TeacherCourseVO toListVO(Course course) {
        CourseAudit latest = latestAudit(course.getId());
        return TeacherCourseVO.builder()
                .id(course.getId())
                .name(course.getName())
                .coverUrl(course.getCoverUrl())
                .courseType(course.getCourseType())
                .firstCateId(course.getFirstCateId())
                .secondCateId(course.getSecondCateId())
                .categoryName(categoryService.categoryName(course.getFirstCateId(), course.getSecondCateId()))
                .free(course.getFree())
                .price(course.getPrice())
                .priceYuan(toYuan(course.getPrice()))
                .status(course.getStatus())
                .statusText(CourseSupport.statusText(course.getStatus()))
                .sectionNum(course.getSectionNum())
                .validDuration(course.getValidDuration())
                .auditStatus(latest == null ? null : latest.getAuditStatus())
                .auditStatusText(CourseSupport.auditStatusText(latest == null ? null : latest.getAuditStatus()))
                .auditReason(latest == null ? null : latest.getReason())
                .createTime(course.getCreateTime())
                .build();
    }

    @Transactional
    protected void saveContent(Long courseId, CourseSaveRequest request, boolean insert) {
        CourseContent content = insert ? null : contentMapper.selectById(courseId);
        if (content == null) {
            content = new CourseContent();
            // 数据库设计说明书 3.3.4：course_content.id 与 course.id 一致（1:1）
            content.setId(courseId);
            content.setCreater(UserContext.getUserId());
            content.setDeleted(0);
        }
        content.setCourseIntroduce(request.getCourseIntroduce());
        content.setUsePeople(request.getUsePeople());
        content.setCourseDetail(request.getCourseDetail());
        content.setUpdater(UserContext.getUserId());
        if (insert) {
            contentMapper.insert(content);
        } else {
            contentMapper.updateById(content);
        }
    }

    private void validateCategories(Long firstCateId, Long secondCateId) {
        if (firstCateId == null) {
            throw new BizException("请选择一级分类");
        }
        var first = categoryService.getOrThrow(firstCateId);
        if (first.getLevel() == null || first.getLevel() != 1) {
            throw new BizException("一级分类选择无效");
        }
        if (secondCateId != null) {
            var second = categoryService.getOrThrow(secondCateId);
            if (second.getLevel() == null || second.getLevel() != 2) {
                throw new BizException("二级分类选择无效");
            }
            if (!Objects.equals(second.getParentId(), firstCateId)) {
                throw new BizException("二级分类不属于所选一级分类");
            }
        }
    }

    /** 价格入参解析：优先按「元」精确十进制换算成分，避免浮点误差 */
    private int resolvePrice(CourseSaveRequest request) {
        if (request.getPriceYuan() != null && !request.getPriceYuan().isBlank()) {
            return MoneyUtils.yuanToCents(request.getPriceYuan());
        }
        Integer price = request.getPrice();
        if (price == null) {
            return 0;
        }
        if (price < 0) {
            throw new BizException("课程价格不能为负数");
        }
        return price;
    }

    private BigDecimal toYuan(Integer price) {
        return MoneyUtils.centsToYuan(price);
    }
}
