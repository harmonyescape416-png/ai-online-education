package com.kzai.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.course.dto.CourseQuery;
import com.kzai.course.dto.CourseSnapshot;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseCategory;
import com.kzai.course.entity.CourseChapter;
import com.kzai.course.entity.CourseChapterExtra;
import com.kzai.course.entity.CourseContent;
import com.kzai.course.mapper.CourseChapterMapper;
import com.kzai.course.mapper.CourseContentMapper;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.vo.CourseDetailVO;
import com.kzai.course.vo.CourseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 课程业务：列表（分页+筛选+排序）、详情、搜索。
 * 课程热点缓存 course:{id} 10 分钟（系统设计说明书 8.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseMapper courseMapper;
    private final CourseContentMapper contentMapper;
    private final CourseChapterMapper chapterMapper;
    private final CategoryService categoryService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LearnOutlineService learnOutlineService;

    /**
     * 统计某创建人名下的未删除课程数（M2：用户删除前的关联校验，仅服务间调用）。
     */
    public long countByCreator(Long creatorId) {
        if (creatorId == null) {
            return 0L;
        }
        Long count = courseMapper.selectCount(
                Wrappers.<Course>lambdaQuery().eq(Course::getCreater, creatorId));
        return count == null ? 0L : count;
    }

    public IPage<CourseVO> page(CourseQuery query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 12 : query.getPageSize();

        var wrapper = Wrappers.<Course>lambdaQuery()
                // 需求 3.2.3.1：只展示已上架课程
                .eq(Course::getStatus, Constants.COURSE_STATUS_ONLINE)
                .eq(Course::getDeleted, 0);

        // M3：分类禁用后学员端不可展示（含一级禁用连带隐藏其下课程）
        List<Long> disabled = disabledCategoryIds();
        if (!disabled.isEmpty()) {
            wrapper.and(w -> w.isNull(Course::getFirstCateId)
                    .or().notIn(Course::getFirstCateId, disabled));
            wrapper.and(w -> w.isNull(Course::getSecondCateId)
                    .or().notIn(Course::getSecondCateId, disabled));
        }
        if (query.getCategoryId() != null) {
            wrapper.and(w -> w.eq(Course::getFirstCateId, query.getCategoryId())
                    .or()
                    .eq(Course::getSecondCateId, query.getCategoryId()));
        }
        if (query.getIds() != null && !query.getIds().isBlank()) {
            java.util.List<Long> idList = java.util.Arrays.stream(query.getIds().split(","))
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .map(Long::valueOf)
                    .distinct()
                    .toList();
            if (idList.isEmpty()) {
                wrapper.eq(Course::getId, -1L);
            } else {
                wrapper.in(Course::getId, idList);
            }
        }
        if (query.getMinPrice() != null) {
            wrapper.ge(Course::getPrice, query.getMinPrice());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String kw = query.getKeyword().trim();
            // M15.15：搜索同时匹配课程名称与课程简介（course_content.id=course.id 共键关联，数据库设计 3.3.4）
            wrapper.and(w -> w.like(Course::getName, kw)
                    .or().exists("SELECT 1 FROM course_content cc WHERE cc.id = course.id "
                            + "AND cc.deleted = 0 AND cc.course_introduce LIKE '%" + kw.replace("'", "''") + "%'"));
        }
        applySort(wrapper, query.getSort());

        Page<Course> page = courseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return page.convert(this::toVO);
    }

    public List<CourseVO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        Page<Course> page = new Page<>(1, 20);
        IPage<Course> result = courseMapper.searchPage(page, keyword.trim());
        return result.getRecords().stream().map(this::toVO).toList();
    }

    public CourseDetailVO detail(Long id) {
        Course course = courseMapper.selectById(id);
        if (course == null || (course.getDeleted() != null && course.getDeleted() == 1)) {
            throw new BizException("课程不存在");
        }
        // M3：未上架、已下架或所属分类被禁用的课程对学员不可见（本人/管理员仍可预览）
        if (!visibleToStudent(course) && !isOwnerOrAdmin(course)) {
            throw new BizException("课程不存在或尚未上架");
        }

        String cacheKey = Constants.REDIS_KEY_COURSE + id;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, CourseDetailVO.class);
            }
        } catch (Exception e) {
            // 缓存降级：Redis 不可用时不阻断主流程（系统设计说明书 8.2）
            log.warn("[course] cache read degraded: {}", e.getMessage());
        }

        CourseDetailVO vo = buildDetail(course);

        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(vo),
                    Duration.ofMinutes(10).toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[course] cache write degraded: {}", e.getMessage());
        }
        return vo;
    }

    private CourseDetailVO buildDetail(Course course) {
        CourseContent content = contentMapper.selectById(course.getId());
        List<CourseChapter> chapters = chapterMapper.selectList(
                Wrappers.<CourseChapter>lambdaQuery()
                        .eq(CourseChapter::getCourseId, course.getId())
                        .eq(CourseChapter::getDeleted, 0)
                        .orderByAsc(CourseChapter::getChapterOrder));

        // M5：试看来自教师显式设置并落库（course_chapter_extra.free_preview），
        // 不再按"第一章永远免费"硬编码推断
        Map<Long, CourseChapterExtra> extras = learnOutlineService.extrasOf(course.getId());
        List<CourseDetailVO.ChapterVO> chapterVOs = chapters.stream().map(c -> {
            CourseChapterExtra extra = extras.get(c.getId());
            return CourseDetailVO.ChapterVO.builder()
                    .id(c.getId())
                    .name(c.getName())
                    .chapterOrder(c.getChapterOrder())
                    .description(c.getDescription())
                    .tryWatch(extra != null && Integer.valueOf(1).equals(extra.getFreePreview()))
                    .videoDurationSec(extra == null ? null : extra.getVideoDurationSec())
                    .build();
        }).toList();

        return CourseDetailVO.builder()
                .id(course.getId())
                .name(course.getName())
                .courseType(course.getCourseType())
                .coverUrl(course.getCoverUrl())
                .free(course.getFree())
                .price(course.getPrice())
                .priceYuan(toYuan(course.getPrice()))
                .status(course.getStatus())
                .sectionNum(course.getSectionNum())
                .score(course.getScore())
                .scoreStar(toStar(course.getScore()))
                .validDuration(course.getValidDuration())
                .courseIntroduce(content == null ? null : content.getCourseIntroduce())
                .usePeople(content == null ? null : content.getUsePeople())
                .courseDetail(content == null ? null : content.getCourseDetail())
                .creater(course.getCreater())
                .chapters(chapterVOs)
                .build();
    }

    /**
     * 批量课程快照（M4 内部契约）：供交易服务预下单使用实时课程数据。
     * <p>
     * 不返回价格以外的推算字段；是否可购买由本服务按上架状态、分类启用与购买有效期判定。
     */
    public List<CourseSnapshot> snapshotBatch(List<Long> ids) {
        List<CourseSnapshot> result = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (Long id : ids) {
            if (id == null) {
                result.add(CourseSnapshot.builder().purchasable(false).reason("课程ID非法").build());
                continue;
            }
            Course course = courseMapper.selectById(id);
            if (course == null || Integer.valueOf(1).equals(course.getDeleted())) {
                result.add(CourseSnapshot.builder().id(id).deleted(1)
                        .purchasable(false).reason("课程不存在").build());
                continue;
            }
            boolean categoryEnabled = categoryService.categoryEnabled(course.getFirstCateId())
                    && categoryService.categoryEnabled(course.getSecondCateId());
            String reason = null;
            boolean purchasable = true;
            if (!Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())) {
                purchasable = false;
                reason = "课程未上架";
            } else if (!categoryEnabled) {
                purchasable = false;
                reason = "所属分类已禁用";
            } else if (!withinPurchaseWindow(course)) {
                purchasable = false;
                reason = "不在购买时间范围内";
            }
            result.add(CourseSnapshot.builder()
                    .id(course.getId())
                    .name(course.getName())
                    .coverUrl(course.getCoverUrl())
                    .price(course.getPrice())
                    .validDuration(course.getValidDuration())
                    .status(course.getStatus())
                    .deleted(course.getDeleted())
                    .categoryEnabled(categoryEnabled)
                    .purchasable(purchasable)
                    .reason(reason)
                    .purchaseStartTime(course.getPurchaseStartTime())
                    .purchaseEndTime(course.getPurchaseEndTime())
                    .build());
        }
        return result;
    }

    /** 购买时间范围校验：起止时间为空表示不限制 */
    private boolean withinPurchaseWindow(Course course) {
        LocalDateTime now = LocalDateTime.now();
        return (course.getPurchaseStartTime() == null || !now.isBefore(course.getPurchaseStartTime()))
                && (course.getPurchaseEndTime() == null || !now.isAfter(course.getPurchaseEndTime()));
    }

    /**
     * M7：AI 课程搜索（只读，内部契约）。
     * <p>
     * 复用 searchPage（已过滤未上架/已删除），再显式校验分类启用——
     * 下架或禁用分类的课程绝不被推荐为可购买课程。
     * 返回空列表表示未找到（由 AI 工具向用户如实说明，不伪造结果）。
     */
    public List<com.kzai.course.dto.CourseAiVO> aiSearch(String keyword, Integer limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int size = limit == null || limit < 1 ? 5 : Math.min(limit, 10);
        Page<Course> page = new Page<>(1, size);
        IPage<Course> result = courseMapper.searchPage(page, keyword.trim());
        List<com.kzai.course.dto.CourseAiVO> out = new ArrayList<>();
        for (Course course : result.getRecords()) {
            if (!categoryService.categoryEnabled(course.getFirstCateId())
                    || !categoryService.categoryEnabled(course.getSecondCateId())) {
                continue; // 分类被禁用：对学员不可见，AI 不推荐
            }
            out.add(toAiVO(course, null, null, null)); // 搜索列表不含章节标题（详情接口才返回）
        }
        return out;
    }

    /**
     * M7：AI 课程详情（只读，内部契约）。
     * <p>
     * 仅返回学员可见的课程（已上架、分类启用、未逻辑删除）；
     * 未上架/禁用分类课程返回 null（AI 侧按「未找到」处理，不泄露内部状态）。
     */
    public com.kzai.course.dto.CourseAiVO aiDetail(Long id) {
        if (id == null) {
            return null;
        }
        Course course = courseMapper.selectById(id);
        if (course == null || (course.getDeleted() != null && course.getDeleted() == 1)) {
            return null;
        }
        boolean categoryEnabled = categoryService.categoryEnabled(course.getFirstCateId())
                && categoryService.categoryEnabled(course.getSecondCateId());
        if (!Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus()) || !categoryEnabled) {
            return null;
        }
        CourseContent content = contentMapper.selectById(course.getId());
        Long chapterCount = chapterMapper.selectCount(Wrappers.<CourseChapter>lambdaQuery()
                .eq(CourseChapter::getCourseId, course.getId())
                .eq(CourseChapter::getDeleted, 0));
        // M15 REAL：真实章节标题与顺序（course_chapter，按 chapter_order 升序）——
        // 模型章节目录的唯一合法数据源，防止自由发挥编造目录
        List<CourseChapter> chapters = chapterMapper.selectList(Wrappers.<CourseChapter>lambdaQuery()
                .eq(CourseChapter::getCourseId, course.getId())
                .eq(CourseChapter::getDeleted, 0)
                .orderByAsc(CourseChapter::getChapterOrder));
        List<com.kzai.course.dto.CourseAiVO.ChapterBrief> briefs = chapters.stream()
                .map(c -> com.kzai.course.dto.CourseAiVO.ChapterBrief.builder()
                        .name(c.getName())
                        .order(c.getChapterOrder())
                        .build())
                .toList();
        var result = toAiVO(course,
                content == null ? null : content.getCourseIntroduce(),
                chapterCount == null ? 0L : chapterCount,
                briefs);
        result.setUsePeople(content == null ? null : content.getUsePeople());
        result.setCategoryId(course.getSecondCateId() == null ? null : String.valueOf(course.getSecondCateId()));
        Map<Long, CourseChapterExtra> durationExtras = learnOutlineService.extrasOf(course.getId());
        if (!chapters.isEmpty() && chapters.stream().allMatch(c -> durationExtras.get(c.getId()) != null
                && durationExtras.get(c.getId()).getVideoDurationSec() != null
                && durationExtras.get(c.getId()).getVideoDurationSec() > 0)) {
            result.setTotalDurationSeconds(chapters.stream().mapToLong(c -> durationExtras.get(c.getId()).getVideoDurationSec()).sum());
        }
        return result;
    }

    private com.kzai.course.dto.CourseAiVO toAiVO(Course course, String introduce, Long chapterCount,
                                                  List<com.kzai.course.dto.CourseAiVO.ChapterBrief> chapters) {
        boolean categoryEnabled = categoryService.categoryEnabled(course.getFirstCateId())
                && categoryService.categoryEnabled(course.getSecondCateId());
        boolean purchasable = Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())
                && categoryEnabled && withinPurchaseWindow(course);
        String reason = null;
        if (!purchasable) {
            if (!Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())) {
                reason = "课程未上架";
            } else if (!categoryEnabled) {
                reason = "所属分类已禁用";
            } else {
                reason = "不在购买时间范围内";
            }
        }
        return com.kzai.course.dto.CourseAiVO.builder()
                .id(String.valueOf(course.getId()))
                .name(course.getName())
                .coverUrl(course.getCoverUrl())
                .price(course.getPrice())
                .priceYuan(toYuan(course.getPrice()) == null ? null : toYuan(course.getPrice()).toPlainString())
                .introduce(introduce)
                .chapterCount(chapterCount)
                .chapters(chapters)
                .courseType(course.getCourseType())
                .free(course.getFree())
                .status(course.getStatus())
                .validDuration(course.getValidDuration())
                .purchasable(purchasable)
                .reason(reason)
                .build();
    }

    /** 学员可见：已上架且所属分类均启用 */
    private boolean visibleToStudent(Course course) {
        if (!Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())) {
            return false;
        }
        return categoryService.categoryEnabled(course.getFirstCateId())
                && categoryService.categoryEnabled(course.getSecondCateId());
    }

    /** 课程创建人或后台管理员可预览未上架课程 */
    private boolean isOwnerOrAdmin(Course course) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return false;
        }
        return CourseSupport.isBackstageAdmin() || userId.equals(course.getCreater());
    }

    /** 已禁用的分类ID（含被禁用一级分类下的二级分类） */
    private List<Long> disabledCategoryIds() {
        List<CourseCategory> all = categoryService.all();
        List<Long> disabled = new ArrayList<>();
        for (CourseCategory category : all) {
            if (!Integer.valueOf(1).equals(category.getStatus())) {
                disabled.add(category.getId());
                continue;
            }
            Long parentId = category.getParentId();
            if (parentId != null && parentId != 0L) {
                CourseCategory parent = categoryService.getOrThrow(parentId);
                if (!Integer.valueOf(1).equals(parent.getStatus())) {
                    disabled.add(category.getId());
                }
            }
        }
        return disabled;
    }

    private void applySort(LambdaQueryWrapper<Course> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByDesc(Course::getCreateTime);
            return;
        }
        switch (sort.trim().toLowerCase()) {
            case "price" -> wrapper.orderByAsc(Course::getPrice);
            // M15.14-R2 C2：价格降序（需求 3.2.3.1 价格升/降排序）
            case "price_asc" -> wrapper.orderByAsc(Course::getPrice);
            case "price_desc" -> wrapper.orderByDesc(Course::getPrice);
            // 需求 3.2.3.1 热度：以评分作为热度排序依据（12 张核心表无销量字段，真实指标）
            case "hot" -> wrapper.orderByDesc(Course::getScore);
            default -> wrapper.orderByDesc(Course::getCreateTime);
        }
    }

    private CourseVO toVO(Course course) {
        return CourseVO.builder()
                .id(course.getId())
                .name(course.getName())
                .courseType(course.getCourseType())
                .coverUrl(course.getCoverUrl())
                .firstCateId(course.getFirstCateId())
                .secondCateId(course.getSecondCateId())
                .free(course.getFree())
                .price(course.getPrice())
                .priceYuan(toYuan(course.getPrice()))
                .status(course.getStatus())
                .sectionNum(course.getSectionNum())
                .score(course.getScore())
                .scoreStar(toStar(course.getScore()))
                .createTime(course.getCreateTime())
                .build();
    }

    private BigDecimal toYuan(Integer price) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(price).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal toStar(Integer score) {
        if (score == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(score).divide(BigDecimal.valueOf(10), 1, RoundingMode.HALF_UP);
    }
}
