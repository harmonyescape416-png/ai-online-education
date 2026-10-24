package com.kzai.learning.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import com.kzai.learning.dto.CourseOutlineDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 课程服务内部接口（M5）：学习服务只读取课程/章节/资源，不复制课程数据。
 * 网关拒绝外部访问 /internal/**，Feign 调用自动附加内部签名。
 */
@FeignClient(name = "kzai-course", path = "/internal/courses",
        configuration = InternalAuthFeignConfig.class)
public interface CourseClient {

    /** 学习大纲：课程快照 + 章节（含试看标识）+ 资源 */
    @GetMapping("/{courseId}/learn-outline")
    R<CourseOutlineDTO> learnOutline(@PathVariable("courseId") Long courseId);

    /** 课程归属判定：返回 courseId → 是否由该用户创建 */
    @GetMapping("/ownership")
    R<Map<Long, Boolean>> ownership(@RequestParam("userId") Long userId,
                                    @RequestParam("courseIds") List<Long> courseIds);

    /** 教师名下课程（学情统计的课程筛选下拉） */
    @GetMapping("/by-creator/{userId}")
    R<List<CourseOutlineDTO>> byCreator(@PathVariable("userId") Long userId);
}
