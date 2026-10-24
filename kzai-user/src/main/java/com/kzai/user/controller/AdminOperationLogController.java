package com.kzai.user.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.user.entity.OperationLog;
import com.kzai.user.service.OperationLogQueryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * M13 操作日志管理（管理员专用，/admin/operation-logs → kzai-user）。
 * 仅 ADMIN；STUDENT/TEACHER 403。
 */
@RestController
@RequestMapping("/admin/operation-logs")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
public class AdminOperationLogController {

    private final OperationLogQueryService service;

    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(required = false) Long operatorId,
                                       @RequestParam(required = false) String module,
                                       @RequestParam(required = false) String operation,
                                       @RequestParam(required = false) String result,
                                       @RequestParam(required = false) String requestId,
                                       @RequestParam(required = false) String startTime,
                                       @RequestParam(required = false) String endTime,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return R.ok(service.page(operatorId, module, operation, result, requestId,
                parseTime(startTime, true), parseTime(endTime, false), page, size));
    }

    @GetMapping("/{id}")
    public R<OperationLog> detail(@PathVariable long id) {
        return R.ok(service.detail(id));
    }

    /** CSV 导出（UTF-8 BOM + 公式注入防护） */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long operatorId,
                       @RequestParam(required = false) String module,
                       @RequestParam(required = false) String operation,
                       @RequestParam(required = false) String result,
                       @RequestParam(required = false) String startTime,
                       @RequestParam(required = false) String endTime,
                       HttpServletResponse response) throws IOException {
        String csv = service.exportCsv(operatorId, module, operation, result,
                parseTime(startTime, true), parseTime(endTime, false));
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=operation-logs.csv");
        response.getOutputStream().write(csv.getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    private static LocalDateTime parseTime(String s, boolean start) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(s.length() == 10 ? s + (start ? "T00:00:00" : "T23:59:59") : s);
    }
}
