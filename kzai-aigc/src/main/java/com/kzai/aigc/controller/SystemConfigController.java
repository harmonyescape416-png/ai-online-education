package com.kzai.aigc.controller;

import com.kzai.aigc.config.ConfigParamService;
import com.kzai.aigc.config.entity.SysConfigPublish;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M13 系统参数管理（管理员专用，/admin/system-settings → kzai-aigc）。
 * 权限：后端 @RequireRoles(ADMIN) 强制。
 */
@RestController
@RequestMapping("/admin/system-settings")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
public class SystemConfigController {

    private final ConfigParamService service;

    /** 参数列表（含说明/草稿/生效值/运行时值/范围/版本） */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(service.list());
    }

    /** 保存草稿（范围校验 + version 乐观锁；不影响运行） */
    public record DraftRequest(Long id, String draftValue, Integer expectedVersion) { }

    @PostMapping("/draft")
    public R<Map<String, Object>> saveDraft(@RequestBody DraftRequest req) {
        service.saveDraft(req.id(), req.draftValue(), req.expectedVersion(),
                UserContext.getUserId(), UserContext.getUsername());
        return R.ok(Map.of("saved", true));
    }

    /** 发布（Nacos 写入 → 有界回读 → 热生效 → 运行时验证；失败自动保留最后有效配置） */
    @PostMapping("/publish")
    public R<Map<String, Object>> publish() {
        return R.ok(service.publish(UserContext.getUserId(), UserContext.getUsername()));
    }

    /** 发布历史 */
    @GetMapping("/publishes")
    public R<List<SysConfigPublish>> publishes(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "10") long size) {
        return R.ok(service.publishes(page, size));
    }
}
