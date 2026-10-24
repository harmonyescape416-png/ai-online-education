package com.kzai.course.controller;

import com.kzai.common.result.R;
import com.kzai.course.dto.LearnOutlineDTO;
import com.kzai.course.service.CourseService;
import com.kzai.course.service.LearnOutlineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 课程服务内部接口：供用户服务做删除前关联校验、供交易服务预下单、供学习服务取学习大纲。
 * 网关拒绝外部访问 /internal/**，仅服务间调用可达。
 */
@RestController
@RequestMapping("/internal/courses")
@RequiredArgsConstructor
public class InternalCourseController {

    private final CourseService courseService;
    private final LearnOutlineService learnOutlineService;
    private final com.kzai.course.mapper.CourseMapper courseMapper;
    private final com.kzai.course.service.ResourceService resourceService;

    @GetMapping("/count-by-creator/{userId}")
    public R<Long> countByCreator(@PathVariable("userId") Long userId) {
        return R.ok(courseService.countByCreator(userId));
    }

    /** M13 运营看板：课程总数（未删除；真实 DB 统计） */
    @GetMapping("/stats/count")
    public R<Long> statsCount() {
        return R.ok(courseMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.kzai.course.entity.Course>lambdaQuery()
                        .eq(com.kzai.course.entity.Course::getDeleted, 0)));
    }

    /**
     * 批量课程快照（M4：交易服务预下单取实时课程数据）。
     * ids 为逗号分隔的课程ID。
     */
    @GetMapping("/batch")
    public R<List<com.kzai.course.dto.CourseSnapshot>> batch(@RequestParam("ids") String ids) {
        List<Long> courseIds = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
        return R.ok(courseService.snapshotBatch(courseIds));
    }

    /** 给 aigc 读取真实文件字节。不接受外部路径，也不按学员权益放开付费资料。 */
    @GetMapping("/resources/{id}/content")
    public R<java.util.Map<String, Object>> resourceContent(@PathVariable("id") Long id) {
        return R.ok(resourceService.internalContent(id));
    }

    /** M5：课程学习大纲（课程 + 章节含试看标识 + 资源） */
    @GetMapping("/{courseId}/learn-outline")
    public R<LearnOutlineDTO> learnOutline(@PathVariable("courseId") Long courseId) {
        return R.ok(learnOutlineService.outline(courseId));
    }

    /** M5：课程归属判定，供学习服务校验教师只能查看本人课程学情 */
    @GetMapping("/ownership")
    public R<Map<Long, Boolean>> ownership(@RequestParam("userId") Long userId,
                                           @RequestParam("courseIds") List<Long> courseIds) {
        return R.ok(learnOutlineService.ownership(userId, courseIds));
    }

    /** M5：教师名下课程，供学情统计筛选 */
    @GetMapping("/by-creator/{userId}")
    public R<List<LearnOutlineDTO>> byCreator(@PathVariable("userId") Long userId) {
        return R.ok(learnOutlineService.byCreator(userId));
    }

    /**
     * M7：AI 课程搜索（只读）。
     * 供 aigc 服务 CourseTools 调用；只返回学员可见课程（已上架、分类启用、未删除）。
     * keyword 为空返回空列表；limit 上限 10。
     */
    @GetMapping("/ai-search")
    public R<List<com.kzai.course.dto.CourseAiVO>> aiSearch(@RequestParam("keyword") String keyword,
                                                            @RequestParam(value = "limit", required = false) Integer limit) {
        return R.ok(courseService.aiSearch(keyword, limit));
    }

    /**
     * M7：AI 课程详情（只读，原始系统设计中的 /courses/{id}/ai 按内部契约适配实现）。
     * 课程不存在、未上架或分类被禁用时返回 data=null（由 AI 按「未找到」处理）。
     */
    @GetMapping("/{courseId}/ai")
    public R<com.kzai.course.dto.CourseAiVO> aiDetail(@PathVariable("courseId") Long courseId) {
        return R.ok(courseService.aiDetail(courseId));
    }
}
