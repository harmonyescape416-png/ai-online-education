package com.kzai.aigc.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 课程服务内部 Feign 客户端（M7，只读）。
 * <p>
 * 复用 kzai-course 的 /internal/courses 契约（网关拒绝外部访问 /internal/**），
 * 走 M2–M6 的内部签名与服务认证机制；用户身份由 InternalAuthFeignInterceptor
 * 从本服务已鉴权的 UserContext 传播，AI 工具执行前必须先恢复 UserContext。
 */
@FeignClient(name = "kzai-course", path = "/internal/courses",
        configuration = InternalAuthFeignConfig.class)
public interface CourseClient {

    @GetMapping("/recommendation-source")
    R<List<Map<String, Object>>> recommendationSource(@RequestParam("afterId") long afterId,
                                                      @RequestParam("limit") int limit);

    /** M7：AI 课程搜索（只读，仅学员可见课程） */
    @GetMapping("/ai-search")
    R<List<CourseAiItem>> aiSearch(@RequestParam("keyword") String keyword,
                                   @RequestParam(value = "limit", required = false) Integer limit);

    /** M7：AI 课程详情（原始设计 /courses/{id}/ai 的内部契约适配）；不可见时 data=null */
    @GetMapping("/{courseId}/ai")
    R<CourseAiItem> aiDetail(@PathVariable("courseId") Long courseId);

    /** 批量课程快照（复用 M4 契约，供订单工具核验课程实时状态） */
    @GetMapping("/batch")
    R<List<CourseSnapshotItem>> batch(@RequestParam("ids") String ids);

    /** 受控读取课程资料字节。只接受资源 ID，不接受调用方传入的路径。 */
    @GetMapping("/resources/{id}/content")
    R<Map<String, Object>> resourceContent(@PathVariable("id") Long id);
}
