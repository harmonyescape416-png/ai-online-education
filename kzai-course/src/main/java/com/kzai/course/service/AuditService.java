package com.kzai.course.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseAudit;
import com.kzai.course.entity.CourseChapter;
import com.kzai.course.entity.CourseContent;
import com.kzai.course.entity.CourseResource;
import com.kzai.course.mapper.CourseAuditMapper;
import com.kzai.course.mapper.CourseChapterMapper;
import com.kzai.course.mapper.CourseContentMapper;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.mapper.CourseResourceMapper;
import com.kzai.course.vo.AuditPreviewVO;
import com.kzai.course.vo.ChapterManageVO;
import com.kzai.course.vo.ResourceVO;
import com.kzai.course.vo.TeacherCourseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * 管理员课程审核（M3）：待审核列表、预览、通过、驳回、强制下架。
 * <p>
 * 状态语义：course.status 沿用原文枚举（1待上架/2已上架/3下架/4已完结），
 * 审核过程态记录在 course_audit，两者不混淆。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final CourseMapper courseMapper;
    private final CourseContentMapper contentMapper;
    private final CourseChapterMapper chapterMapper;
    private final CourseResourceMapper resourceMapper;
    private final CourseAuditMapper auditMapper;
    private final CategoryService categoryService;
    private final TeacherCourseService teacherCourseService;
    private final StringRedisTemplate redisTemplate;
    private final com.kzai.course.notify.NotificationOutboxService notificationOutbox;
    private final com.kzai.course.service.CourseAuditBridge auditBridge; // M13 审计落库

    /** 待审核课程列表（course_audit 中最新状态为待审核） */
    public IPage<TeacherCourseVO> pendingList(Integer pageNum, Integer pageSize, String name) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : pageSize;

        // 只按「每门课程的最新一条审核记录」判定待审核，
        // 避免已通过/已驳回课程的歷史待审核记录残留在待审核列表中
        List<CourseAudit> all = auditMapper.selectList(
                Wrappers.<CourseAudit>lambdaQuery()
                        .orderByDesc(CourseAudit::getCreateTime)
                        .orderByDesc(CourseAudit::getId));
        Map<Long, CourseAudit> latestByCourse = new LinkedHashMap<>();
        for (CourseAudit audit : all) {
            latestByCourse.putIfAbsent(audit.getCourseId(), audit);
        }
        Set<Long> courseIds = new LinkedHashSet<>();
        for (CourseAudit audit : latestByCourse.values()) {
            if (Integer.valueOf(Constants.AUDIT_PENDING).equals(audit.getAuditStatus())) {
                courseIds.add(audit.getCourseId());
            }
        }
        if (courseIds.isEmpty()) {
            return new Page<>(num, size);
        }
        var wrapper = Wrappers.<Course>lambdaQuery()
                .in(Course::getId, courseIds)
                .eq(Course::getDeleted, 0);
        if (StringUtils.hasText(name)) {
            wrapper.like(Course::getName, name.trim());
        }
        wrapper.orderByAsc(Course::getCreateTime);
        Page<Course> page = courseMapper.selectPage(new Page<>(num, size), wrapper);
        return page.convert(course -> {
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
                    .auditStatus(latest == null ? null : latest.getAuditStatus())
                    .auditStatusText(CourseSupport.auditStatusText(latest == null ? null : latest.getAuditStatus()))
                    .auditReason(latest == null ? null : latest.getReason())
                    .createTime(course.getCreateTime())
                    .build();
        });
    }

    /** 审核预览：课程信息 + 内容 + 章节 + 资料 */
    public AuditPreviewVO preview(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || Integer.valueOf(1).equals(course.getDeleted())) {
            throw new BizException("课程不存在");
        }
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

        List<ChapterManageVO> chapterVOs = new ArrayList<>();
        for (CourseChapter chapter : chapters) {
            List<ResourceVO> chapterResources = new ArrayList<>();
            for (CourseResource resource : resources) {
                if (courseId.equals(resource.getCourseId())
                        && chapter.getId().equals(resource.getChapterId())) {
                    chapterResources.add(ResourceService.toVO(resource));
                }
            }
            chapterVOs.add(ChapterManageVO.builder()
                    .id(chapter.getId())
                    .courseId(chapter.getCourseId())
                    .name(chapter.getName())
                    .chapterOrder(chapter.getChapterOrder())
                    .description(chapter.getDescription())
                    .resources(chapterResources)
                    .createTime(chapter.getCreateTime())
                    .build());
        }

        return AuditPreviewVO.builder()
                .id(course.getId())
                .name(course.getName())
                .coverUrl(course.getCoverUrl())
                .courseType(course.getCourseType())
                .categoryName(categoryService.categoryName(course.getFirstCateId(), course.getSecondCateId()))
                .free(course.getFree())
                .price(course.getPrice())
                .priceYuan(toYuan(course.getPrice()))
                .status(course.getStatus())
                .statusText(CourseSupport.statusText(course.getStatus()))
                .validDuration(course.getValidDuration())
                .usePeople(content == null ? null : content.getUsePeople())
                .courseIntroduce(content == null ? null : content.getCourseIntroduce())
                .courseDetail(content == null ? null : content.getCourseDetail())
                .creater(course.getCreater())
                .createTime(course.getCreateTime())
                .auditStatus(latest == null ? null : latest.getAuditStatus())
                .auditStatusText(CourseSupport.auditStatusText(latest == null ? null : latest.getAuditStatus()))
                .auditReason(latest == null ? null : latest.getReason())
                .chapters(chapterVOs)
                .resources(resources.stream().map(ResourceService::toVO).toList())
                .build();
    }

    /** 审核通过：待上架 → 已上架 */
    @Transactional
    public void approve(Long courseId) {
        Course course = requireCourse(courseId);
        CourseAudit latest = latestAudit(courseId);
        if (latest == null) {
            throw new BizException("课程尚未提交审核");
        }
        if (!Integer.valueOf(Constants.AUDIT_PENDING).equals(latest.getAuditStatus())) {
            throw new BizException("当前审核状态不可通过：" + CourseSupport.auditStatusText(latest.getAuditStatus()));
        }
        CourseSupport.checkTransition(Constants.COURSE_STATUS_PENDING, Constants.COURSE_STATUS_ONLINE);
        if (!categoryService.categoryEnabled(course.getFirstCateId())
                || !categoryService.categoryEnabled(course.getSecondCateId())) {
            throw new BizException("所属分类已禁用，无法上架");
        }
        course.setStatus(Constants.COURSE_STATUS_ONLINE);
        course.setUpdater(UserContext.getUserId());
        courseMapper.updateById(course);
        CourseAudit audit = record(courseId, Constants.AUDIT_APPROVED, "审核通过");
        // M11：审核通过事务内入队通知事件（以真实审核记录为依据；驳回/失败/回滚不产生通知）
        if (course.getCreater() != null && audit != null) {
            notificationOutbox.enqueue("COURSE_APPROVED:" + audit.getId(), "COURSE",
                    String.valueOf(courseId), course.getCreater(), "COURSE_APPROVED",
                    "课程审核已通过",
                    "课程《" + course.getName() + "》审核通过，现已上架。",
                    "{\"courseId\":\"" + courseId + "\"}");
        }
    }

    /** 驳回：必须填写原因，课程保持待上架 */
    @Transactional
    public void reject(Long courseId, String reason) {
        Course course = requireCourse(courseId);
        CourseAudit latest = latestAudit(courseId);
        if (latest == null || !Integer.valueOf(Constants.AUDIT_PENDING).equals(latest.getAuditStatus())) {
            throw new BizException("仅待审核课程可驳回");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException("驳回必须填写原因");
        }
        record(courseId, Constants.AUDIT_REJECTED, reason.trim());
        // 驳回后课程保持「待上架」，学员端不可见，教师可修改后重新提交
        if (!Integer.valueOf(Constants.COURSE_STATUS_PENDING).equals(course.getStatus())) {
            course.setStatus(Constants.COURSE_STATUS_PENDING);
            course.setUpdater(UserContext.getUserId());
            courseMapper.updateById(course);
        }
    }

    /** 强制下架：已上架 → 下架，必须填写原因 */
    @Transactional
    public void offline(Long courseId, String reason) {
        Course course = requireCourse(courseId);
        if (!Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())) {
            throw new BizException("仅已上架课程可强制下架，当前状态：" + CourseSupport.statusText(course.getStatus()));
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException("强制下架必须填写原因");
        }
        CourseSupport.checkTransition(Constants.COURSE_STATUS_ONLINE, Constants.COURSE_STATUS_OFFLINE);
        course.setStatus(Constants.COURSE_STATUS_OFFLINE);
        course.setUpdater(UserContext.getUserId());
        courseMapper.updateById(course);
        record(courseId, Constants.AUDIT_OFFLINE, reason.trim());
    }

    public CourseAudit latestAudit(Long courseId) {
        List<CourseAudit> audits = auditMapper.selectList(
                Wrappers.<CourseAudit>lambdaQuery()
                        .eq(CourseAudit::getCourseId, courseId)
                        .orderByDesc(CourseAudit::getCreateTime)
                        .orderByDesc(CourseAudit::getId));
        return audits.isEmpty() ? null : audits.get(0);
    }

    private CourseAudit record(Long courseId, int auditStatus, String reason) {
        CourseAudit audit = new CourseAudit();
        audit.setCourseId(courseId);
        audit.setAuditStatus(auditStatus);
        audit.setReason(reason);
        audit.setAuditorId(UserContext.getUserId());
        audit.setAuditorName(UserContext.getUsername());
        auditMapper.insert(audit);
        teacherCourseService.evictCache(courseId);
        log.info("[course-audit] course={} status={} operator={} reason={}",
                courseId, auditStatus, UserContext.getUsername(), reason);
        // M13：审核操作真实落 sys_operation_log（approve=2/reject=3/offline=4 → 审计 operation 与之一一对应）
        String op = switch (auditStatus) {
            case Constants.AUDIT_APPROVED -> "COURSE_AUDIT_APPROVE";
            case Constants.AUDIT_REJECTED -> "COURSE_AUDIT_REJECT";
            case Constants.AUDIT_OFFLINE -> "COURSE_OFFLINE";
            default -> "COURSE_AUDIT_" + auditStatus;
        };
        auditBridge.write(UserContext.getUserId(), UserContext.getUsername(), op,
                "course", courseId, "{\"auditStatus\":" + auditStatus
                        + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "'")) + "\"}");
        return audit;
    }

    private Course requireCourse(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || Integer.valueOf(1).equals(course.getDeleted())) {
            throw new BizException("课程不存在");
        }
        return course;
    }

    private BigDecimal toYuan(Integer price) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(price).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
