package com.kzai.course.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.course.dto.ResourceBindRequest;
import com.kzai.course.service.ResourceService;
import com.kzai.course.vo.ResourceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程资料绑定（M3）：把已上传文件绑定到课程/章节，封面绑定会同步 course.cover_url。
 */
@RestController
@RequireRoles({Constants.ROLE_TEACHER, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class TeacherResourceController {

    private final ResourceService resourceService;

    @GetMapping("/teacher/courses/{courseId}/resources")
    public R<List<ResourceVO>> list(@PathVariable("courseId") Long courseId) {
        return R.ok(resourceService.list(courseId));
    }

    /** M15.20-D：本人未绑定文件（上传后绑定失败的恢复入口；仅本人，不暴露全站） */
    @GetMapping("/teacher/resources/unbound")
    public R<List<ResourceVO>> unbound() {
        return R.ok(resourceService.listUnbound(UserContext.getUserId()));
    }

    @PostMapping("/teacher/courses/{courseId}/resources")
    public R<Void> bind(@PathVariable("courseId") Long courseId,
                        @Valid @RequestBody ResourceBindRequest request) {
        resourceService.bind(courseId, request);
        return R.ok();
    }

    /** M15.20-E：资料替换（新文件顶替旧版本，保留历史链） */
    @org.springframework.web.bind.annotation.PostMapping("/teacher/resources/{id}/replace")
    public R<ResourceVO> replace(@org.springframework.web.bind.annotation.PathVariable("id") Long id,
                                 @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return R.ok(ResourceService.toVO(resourceService.replace(id, file)));
    }

    /** M15.20-E：历史版本链（本人可见） */
    @GetMapping("/teacher/resources/{id}/history")
    public R<List<ResourceVO>> history(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        return R.ok(resourceService.history(id));
    }

    /** M15.20-E：一键加入知识库（提交管理员审核；幂等） */
    @org.springframework.web.bind.annotation.PostMapping("/teacher/resources/{id}/submit-kb")
    public R<Boolean> submitKb(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        resourceService.submitKb(id);
        return R.ok(Boolean.TRUE);
    }

    /** 已审核且索引失败时重新入队。不新建文档，也不能绕过审核。 */
    @PostMapping("/teacher/resources/{id}/retry-kb")
    public R<java.util.Map<String, Object>> retryKb(@PathVariable("id") Long id) {
        return R.ok(resourceService.retryKb(id));
    }

    @DeleteMapping("/teacher/resources/{id}")
    public R<Void> remove(@PathVariable("id") Long id) {
        resourceService.remove(id);
        return R.ok();
    }
}
