package com.kzai.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.exception.BizException;
import com.kzai.common.util.CsvUtils;
import com.kzai.user.entity.OperationLog;
import com.kzai.user.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 操作日志管理（管理员专用）：
 * <ul>
 *   <li>分页 + 操作人/模块/操作类型/结果/日期范围/requestId 组合筛选；
 *       result 与 requestId 承载于 detail JSON 约定键（{@code "result":"SUCCESS|FAIL"}、
 *       {@code "requestId":"..."}），不改动审计表既有字段语义；</li>
 *   <li>CSV 导出：UTF-8 BOM + 公式注入防护（common CsvUtils）；</li>
 *   <li>本服务仅 ADMIN 可达（控制器 @RequireRoles）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OperationLogQueryService {

    private final OperationLogMapper mapper;

    public Map<String, Object> page(Long operatorId, String module, String operation,
                                    String result, String requestId,
                                    LocalDateTime startTime, LocalDateTime endTime,
                                    long page, long size) {
        var wrapper = Wrappers.<OperationLog>lambdaQuery()
                .eq(operatorId != null, OperationLog::getOperatorId, operatorId)
                .eq(module != null && !module.isBlank(), OperationLog::getModule, module)
                .eq(operation != null && !operation.isBlank(), OperationLog::getOperation, operation)
                .ge(startTime != null, OperationLog::getCreateTime, startTime)
                .le(endTime != null, OperationLog::getCreateTime, endTime)
                .like(requestId != null && !requestId.isBlank(), OperationLog::getDetail, requestId)
                .orderByDesc(OperationLog::getCreateTime);
        if (result != null && !result.isBlank()) {
            wrapper.like(OperationLog::getDetail, "\"result\":\"" + result.trim().toUpperCase() + "\"");
        }
        Page<OperationLog> p = mapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotal());
        out.put("records", p.getRecords());
        return out;
    }

    public OperationLog detail(long id) {
        OperationLog log = mapper.selectById(id);
        if (log == null) {
            throw new BizException("日志不存在");
        }
        return log;
    }

    /** CSV 导出（含表头；UTF-8 BOM 由控制器输出流写入） */
    public String exportCsv(Long operatorId, String module, String operation, String result,
                            LocalDateTime startTime, LocalDateTime endTime) {
        var wrapper = Wrappers.<OperationLog>lambdaQuery()
                .eq(operatorId != null, OperationLog::getOperatorId, operatorId)
                .eq(module != null && !module.isBlank(), OperationLog::getModule, module)
                .eq(operation != null && !operation.isBlank(), OperationLog::getOperation, operation)
                .ge(startTime != null, OperationLog::getCreateTime, startTime)
                .le(endTime != null, OperationLog::getCreateTime, endTime)
                .orderByDesc(OperationLog::getCreateTime)
                .last("LIMIT 5000");
        if (result != null && !result.isBlank()) {
            wrapper.like(OperationLog::getDetail, "\"result\":\"" + result.trim().toUpperCase() + "\"");
        }
        List<OperationLog> logs = mapper.selectList(wrapper);
        StringBuilder sb = new StringBuilder(CsvUtils.UTF8_BOM);
        sb.append(CsvUtils.line("id", "操作人ID", "操作人", "模块", "操作", "对象类型", "对象ID", "详情", "时间"));
        List<String> rows = new ArrayList<>();
        for (OperationLog l : logs) {
            rows.add(CsvUtils.line(
                    CsvUtils.number(l.getId()), CsvUtils.number(l.getOperatorId()),
                    CsvUtils.escape(l.getOperatorName()), CsvUtils.escape(l.getModule()),
                    CsvUtils.escape(l.getOperation()), CsvUtils.escape(l.getTargetType()),
                    CsvUtils.number(l.getTargetId()), CsvUtils.escape(l.getDetail()),
                    CsvUtils.escape(l.getCreateTime())));
        }
        for (String r : rows) {
            sb.append(r);
        }
        return sb.toString();
    }
}
