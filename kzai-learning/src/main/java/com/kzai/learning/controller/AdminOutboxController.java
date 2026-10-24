package com.kzai.learning.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.entity.SysEventOutbox;
import com.kzai.learning.mapper.SysEventOutboxMapper;
import com.kzai.learning.util.OperationAuditLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M13 FAILED Outbox 运维（管理员专用；/admin/outbox → kzai-learning）。
 * <ul>
 *   <li>仅 status=3(FAILED) 事件可重投；重投 = 条件更新 status 3→0、attempt 清零、
 *       next_retry=now、清空租约——<b>eventKey 不变</b>，由 Worker 真实重新投递，
 *       user 侧 UNIQUE(recipient_id,event_key) 保证不重复产生通知；</li>
 *   <li>禁止直接置 SUCCESS 伪造「已送达」；重投结果由真实投递决定；</li>
 *   <li>并发管理员重投：条件更新（仅 FAILED 行受影响）仅一次生效；</li>
 *   <li>重投操作写入 sys_operation_log 审计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/outbox")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
public class AdminOutboxController {

    private final SysEventOutboxMapper outboxMapper;
    private final OperationAuditLogger audit;

    /** FAILED 事件分页（类型/eventKey 筛选） */
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        Page<SysEventOutbox> p = outboxMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                new LambdaQueryWrapper<SysEventOutbox>()
                        .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_FAILED)
                        .eq(type != null && !type.isBlank(), SysEventOutbox::getType, type)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SysEventOutbox::getEventKey, keyword)
                                .or().like(SysEventOutbox::getError, keyword))
                        .orderByDesc(SysEventOutbox::getUpdateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotal());
        out.put("records", p.getRecords());
        return R.ok(out);
    }

    /** 受控重投（仅 FAILED 行；条件更新防并发重复触发；eventKey 保持不变） */
    @PostMapping("/{id}/retry")
    public R<Map<String, Object>> retry(@PathVariable long id) {
        SysEventOutbox row = outboxMapper.selectById(id);
        if (row == null) {
            throw new BizException("事件不存在");
        }
        if (row.getStatus() != SysEventOutbox.STATUS_FAILED) {
            throw new BizException("仅 FAILED 事件可重投（当前状态 " + row.getStatus() + "）");
        }
        SysEventOutbox upd = new SysEventOutbox();
        upd.setStatus(SysEventOutbox.STATUS_PENDING);
        upd.setAttempt(0);
        upd.setNextRetryTime(LocalDateTime.now().minusSeconds(1));
        upd.setWorkerId(null);
        upd.setLeaseUntil(null);
        upd.setError(null);
        upd.setUpdateTime(LocalDateTime.now());
        // 条件更新：仅仍为 FAILED 的行受影响（并发重投仅一次生效）
        int n = outboxMapper.update(upd, Wrappers.<SysEventOutbox>lambdaUpdate()
                .eq(SysEventOutbox::getId, id)
                .eq(SysEventOutbox::getStatus, SysEventOutbox.STATUS_FAILED));
        if (n != 1) {
            throw new BizException("该事件已被其他管理员重投");
        }
        long adminId = UserContext.getUserId();
        String adminName = UserContext.getUsername();
        audit.log(adminId, adminName, "OUTBOX", "EVENT_RETRY",
                "sys_event_outbox", id,
                "{\"eventKey\":\"" + row.getEventKey() + "\",\"type\":\"" + row.getType()
                        + "\",\"prevError\":\"" + esc(row.getError()) + "\"}");
        return R.ok(Map.of("requeued", true, "eventKey", row.getEventKey()));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
