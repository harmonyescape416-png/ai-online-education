package com.kzai.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.BizException;
import com.kzai.course.dto.CategorySaveRequest;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseCategory;
import com.kzai.course.mapper.CourseCategoryMapper;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 课程分类管理（M3）。
 * <p>
 * 需求要求先支持两级管理：本服务只允许创建/维护一、二级分类，
 * 但保留 course 表原有的 first/second/third 三级字段语义，不改动核心表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CourseCategoryMapper categoryMapper;
    private final CourseMapper courseMapper;
    private final StringRedisTemplate redisTemplate;

    /** 分类树：onlyEnabled=true 时只返回启用分类（学员端） */
    public List<CategoryVO> tree(boolean onlyEnabled) {
        var wrapper = Wrappers.<CourseCategory>lambdaQuery();
        if (onlyEnabled) {
            wrapper.eq(CourseCategory::getStatus, 1);
        }
        List<CourseCategory> all = categoryMapper.selectList(
                wrapper.orderByAsc(CourseCategory::getLevel).orderByAsc(CourseCategory::getSort));
        return buildTree(all);
    }

    public Long create(CategorySaveRequest request) {
        String name = normalizeName(request.getName());
        Long parentId = request.getParentId() == null ? 0L : request.getParentId();
        int level;
        if (parentId == 0L) {
            level = 1;
        } else {
            CourseCategory parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException("父分类不存在");
            }
            // 需求：本阶段支持两级管理，三级分类暂不开放创建（字段语义保留）
            if (parent.getLevel() == null || parent.getLevel() != 1) {
                throw new BizException("仅支持两级分类，父分类必须是一级分类");
            }
            level = 2;
        }
        checkDuplicateName(name, parentId, null);

        CourseCategory category = new CourseCategory();
        category.setName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        category.setSort(request.getSort() == null ? 100 : request.getSort());
        category.setStatus(request.getStatus() == null ? 1 : normalizeStatus(request.getStatus()));
        categoryMapper.insert(category);
        return category.getId();
    }

    @Transactional
    public void update(Long id, CategorySaveRequest request) {
        CourseCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException("分类不存在");
        }
        String name = normalizeName(request.getName());
        Long parentId = request.getParentId() == null ? category.getParentId() : request.getParentId();
        int level;
        if (parentId == null || parentId == 0L) {
            parentId = 0L;
            level = 1;
        } else {
            if (Objects.equals(parentId, id)) {
                throw new BizException("父分类不能是自己");
            }
            CourseCategory parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException("父分类不存在");
            }
            if (isDescendant(id, parentId)) {
                throw new BizException("父分类不能是自己的子分类");
            }
            if (parent.getLevel() == null || parent.getLevel() != 1) {
                throw new BizException("仅支持两级分类，父分类必须是一级分类");
            }
            level = 2;
        }
        checkDuplicateName(name, parentId, id);

        category.setName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        if (request.getSort() != null) {
            category.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            category.setStatus(normalizeStatus(request.getStatus()));
        }
        categoryMapper.updateById(category);
        if (Integer.valueOf(0).equals(category.getStatus())) {
            evictCourseCache(id);
        }
    }

    public void updateStatus(Long id, Integer status) {
        CourseCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException("分类不存在");
        }
        category.setStatus(normalizeStatus(status));
        categoryMapper.updateById(category);
        // 一级分类禁用时，其下二级分类一并禁用，避免学员端从二级入口看到已禁用一级下的课程
        List<Long> affected = new ArrayList<>(List.of(id));
        List<CourseCategory> children = categoryMapper.selectList(
                Wrappers.<CourseCategory>lambdaQuery().eq(CourseCategory::getParentId, id));
        if (category.getStatus() == 0) {
            for (CourseCategory child : children) {
                child.setStatus(0);
                categoryMapper.updateById(child);
                affected.add(child.getId());
            }
        }
        // 分类启停影响学员端可见性，需失效相关课程缓存（键与 CourseService 一致：course:{id}）
        for (Long categoryId : affected) {
            evictCourseCache(categoryId);
        }
    }

    /** 删除前校验：存在子分类或课程引用时拒绝删除 */
    public void delete(Long id) {
        CourseCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException("分类不存在");
        }
        long children = categoryMapper.selectCount(
                Wrappers.<CourseCategory>lambdaQuery().eq(CourseCategory::getParentId, id));
        if (children > 0) {
            throw new BizException("存在子分类，无法删除");
        }
        long refs = countCourseRefs(id);
        if (refs > 0) {
            throw new BizException("该分类下存在 " + refs + " 门课程，无法删除");
        }
        categoryMapper.deleteById(id);
    }

    /** 失效引用该分类的课程缓存，避免禁用/启用后学员端读到旧数据 */
    private void evictCourseCache(Long categoryId) {
        List<Long> courseIds = new ArrayList<>();
        courseIds.addAll(courseIdsBy(Wrappers.<Course>lambdaQuery().eq(Course::getFirstCateId, categoryId)));
        courseIds.addAll(courseIdsBy(Wrappers.<Course>lambdaQuery().eq(Course::getSecondCateId, categoryId)));
        courseIds.addAll(courseIdsBy(Wrappers.<Course>lambdaQuery().eq(Course::getThirdCateId, categoryId)));
        try {
            for (Long courseId : courseIds) {
                redisTemplate.delete(Constants.REDIS_KEY_COURSE + courseId);
            }
        } catch (Exception e) {
            log.warn("[category] cache evict degraded: {}", e.getMessage());
        }
    }

    private List<Long> courseIdsBy(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Course> wrapper) {
        return courseMapper.selectList(wrapper).stream().map(Course::getId).toList();
    }

    /** 统计引用该分类的课程数（三级字段均计入，保持原有语义） */
    public long countCourseRefs(Long categoryId) {
        Long first = courseMapper.selectCount(Wrappers.<Course>lambdaQuery()
                .eq(Course::getFirstCateId, categoryId).eq(Course::getDeleted, 0));
        Long second = courseMapper.selectCount(Wrappers.<Course>lambdaQuery()
                .eq(Course::getSecondCateId, categoryId).eq(Course::getDeleted, 0));
        Long third = courseMapper.selectCount(Wrappers.<Course>lambdaQuery()
                .eq(Course::getThirdCateId, categoryId).eq(Course::getDeleted, 0));
        return (first == null ? 0 : first) + (second == null ? 0 : second) + (third == null ? 0 : third);
    }

    /** 全量分类（含禁用），供可见性判断使用 */
    public List<CourseCategory> all() {
        return categoryMapper.selectList(Wrappers.<CourseCategory>lambdaQuery());
    }

    public CourseCategory getOrThrow(Long id) {
        CourseCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException("分类不存在");
        }
        return category;
    }

    /** 分类是否启用（用于学员端可见性判断） */
    public boolean categoryEnabled(Long categoryId) {
        if (categoryId == null) {
            return true;
        }
        CourseCategory category = categoryMapper.selectById(categoryId);
        return category != null && Integer.valueOf(1).equals(category.getStatus());
    }

    /** 学员端可用分类ID（启用） */
    public List<Long> enabledCategoryIds() {
        return categoryMapper.selectList(
                        Wrappers.<CourseCategory>lambdaQuery().eq(CourseCategory::getStatus, 1))
                .stream().map(CourseCategory::getId).toList();
    }

    public String categoryName(Long firstCateId, Long secondCateId) {
        StringBuilder sb = new StringBuilder();
        if (firstCateId != null) {
            CourseCategory first = categoryMapper.selectById(firstCateId);
            if (first != null) {
                sb.append(first.getName());
            }
        }
        if (secondCateId != null) {
            CourseCategory second = categoryMapper.selectById(secondCateId);
            if (second != null) {
                if (!sb.isEmpty()) {
                    sb.append(" / ");
                }
                sb.append(second.getName());
            }
        }
        return sb.toString();
    }

    private List<CategoryVO> buildTree(List<CourseCategory> all) {
        Map<Long, CategoryVO> nodes = new LinkedHashMap<>();
        for (CourseCategory category : all) {
            nodes.put(category.getId(), CategoryVO.builder()
                    .id(category.getId())
                    .name(category.getName())
                    .parentId(category.getParentId())
                    .level(category.getLevel())
                    .sort(category.getSort())
                    .status(category.getStatus())
                    .children(new ArrayList<>())
                    .build());
        }
        List<CategoryVO> roots = new ArrayList<>();
        for (CategoryVO node : nodes.values()) {
            Long parentId = node.getParentId();
            if (parentId == null || parentId == 0L || !nodes.containsKey(parentId)) {
                roots.add(node);
            } else {
                nodes.get(parentId).getChildren().add(node);
            }
        }
        return roots;
    }

    private boolean isDescendant(Long ancestorId, Long candidateId) {
        Long cursor = candidateId;
        for (int i = 0; i < 10 && cursor != null && cursor != 0L; i++) {
            if (Objects.equals(cursor, ancestorId)) {
                return true;
            }
            CourseCategory current = categoryMapper.selectById(cursor);
            if (current == null) {
                return false;
            }
            cursor = current.getParentId();
        }
        return false;
    }

    private void checkDuplicateName(String name, Long parentId, Long excludeId) {
        List<CourseCategory> siblings = categoryMapper.selectList(
                Wrappers.<CourseCategory>lambdaQuery().eq(CourseCategory::getParentId, parentId));
        for (CourseCategory sibling : siblings) {
            if (!Objects.equals(sibling.getId(), excludeId) && name.equals(sibling.getName())) {
                throw new BizException("同级下已存在同名分类");
            }
        }
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BizException("分类名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 30) {
            throw new BizException("分类名称不能超过 30 个字符");
        }
        return trimmed;
    }

    private int normalizeStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("分类状态非法：只能为 0 禁用或 1 启用");
        }
        return status;
    }
}
