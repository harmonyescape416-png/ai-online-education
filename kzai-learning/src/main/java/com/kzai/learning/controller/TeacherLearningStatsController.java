package com.kzai.learning.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.service.TeacherStatsService;
import com.kzai.learning.mapper.LearningLessonMapper;
import com.kzai.learning.vo.TeacherStatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.time.format.DateTimeFormatter;

/**
 * 教师课程学情统计（M5）。
 * <p>
 * 教师只能统计本人创建的课程；后台管理员可按 courseId 统计任意课程。
 */
@RestController
@RequestMapping("/teacher/learning-stats")
@RequireRoles({Constants.ROLE_TEACHER, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class TeacherLearningStatsController {

    private final TeacherStatsService teacherStatsService;
    private final com.kzai.learning.mapper.InteractionQuestionMapper questionMapper;
    private final com.kzai.learning.mapper.InteractionReplyMapper replyMapper;
    private final com.kzai.learning.mapper.LearningLessonMapper lessonMapper;

    /** 学情统计：学习人数、章节学习人数、完成率、章节完成分布、最近学习、平均时长 */
    @GetMapping
    public R<IPage<TeacherStatsVO>> stats(@RequestParam(value = "courseId", required = false) Long courseId,
                                          @RequestParam(value = "pageNum", required = false) Integer pageNum,
                                          @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                          @RequestParam(value = "dateFrom", required = false) String dateFrom,
                                          @RequestParam(value = "dateTo", required = false) String dateTo) {
        return R.ok(teacherStatsService.page(requireUserId(), courseId, pageNum, pageSize, backstageAdmin(), dateFrom, dateTo))
                .requestId(UserContext.getRequestId());
    }

    /** 学情 CSV 导出（UTF-8 BOM + 公式注入防护） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(value = "courseId", required = false) Long courseId,
                                         @RequestParam(value = "dateFrom", required = false) String dateFrom,
                                         @RequestParam(value = "dateTo", required = false) String dateTo) {
        String csv = teacherStatsService.exportCsv(requireUserId(), courseId, backstageAdmin(), dateFrom, dateTo);
        String fileName = "learning-stats-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

        /** M15.20-B：教师工作台摘要（逻辑在 TeacherStatsService——口径修正/下沉） */
    @GetMapping("/workbench-summary")
    public R<java.util.Map<String, Object>> workbenchSummary() {
        return R.ok(teacherStatsService.workbenchSummary(requireUserId(), backstageAdmin()))
                .requestId(UserContext.getRequestId());
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    private boolean backstageAdmin() {
        return UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
    }
}
