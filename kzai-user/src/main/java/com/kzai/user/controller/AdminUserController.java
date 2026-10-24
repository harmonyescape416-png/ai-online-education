package com.kzai.user.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.user.dto.RoleAssignRequest;
import com.kzai.user.dto.ResetPasswordRequest;
import com.kzai.user.dto.StatusUpdateRequest;
import com.kzai.user.dto.UserAdminQuery;
import com.kzai.user.dto.UserCreateRequest;
import com.kzai.user.dto.UserUpdateRequest;
import com.kzai.user.service.UserManageService;
import com.kzai.user.vo.UserAdminVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员用户管理接口（需求 3.2.5.2）。
 * 类级 @RequireRoles 限制仅管理员/员工可访问，写操作进一步收紧为仅管理员。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN, Constants.ROLE_STAFF})
public class AdminUserController {

    private final UserManageService userManageService;

    @GetMapping
    public R<IPage<UserAdminVO>> page(UserAdminQuery query) {
        return R.ok(userManageService.page(query));
    }

    @GetMapping("/{id}")
    public R<UserAdminVO> detail(@PathVariable("id") Long id) {
        return R.ok(userManageService.detail(id));
    }

    @PostMapping
    @RequireRoles(Constants.ROLE_ADMIN)
    public R<UserAdminVO> create(@Valid @RequestBody UserCreateRequest request) {
        return R.ok(userManageService.create(request));
    }

    /**
     * M15.17：CSV 批量导入（仅 ADMIN；≤500 行；UTF-8/BOM；行级校验部分成功）。
     * 字段：username,cell_phone,type（type=STUDENT/TEACHER，禁止 ADMIN）。
     * 初始密码随机生成，随导入结果一次性返回（管理员线下交付）。
     */
    @PostMapping("/import")
    @RequireRoles(Constants.ROLE_ADMIN)
    public R<Map<String, Object>> importUsers(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        if (file == null || file.isEmpty()) {
            throw new com.kzai.common.exception.BizException("请选择 CSV 文件");
        }
        if (file.getSize() > 512 * 1024) {
            throw new com.kzai.common.exception.BizException("文件过大（上限 512KB）");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            bytes = java.util.Arrays.copyOfRange(bytes, 3, bytes.length); // 去 UTF-8 BOM
        }
        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String line : content.split("\r?\n")) {
            if (line.isBlank()) continue;
            String[] cols = line.split(",", -1);
            if (cols.length < 3) {
                throw new com.kzai.common.exception.BizException("CSV 每行需 3 列：username,cell_phone,type");
            }
            rows.add(cols);
        }
        if (rows.isEmpty()) {
            throw new com.kzai.common.exception.BizException("CSV 无数据行");
        }
        // 首行为表头则跳过
        if (rows.get(0)[0] != null && rows.get(0)[0].trim().equalsIgnoreCase("username")) {
            rows.remove(0);
        }
        return R.ok(userManageService.importCsv(rows, UserContext.getUserId()));
    }

    @PutMapping("/{id}")
    @RequireRoles(Constants.ROLE_ADMIN)
    public R<UserAdminVO> update(@PathVariable("id") Long id,
                                 @Valid @RequestBody UserUpdateRequest request) {
        return R.ok(userManageService.update(id, request));
    }

    @PutMapping("/{id}/roles")
    @RequireRoles(Constants.ROLE_ADMIN)
    public R<UserAdminVO> assignRoles(@PathVariable("id") Long id,
                                      @Valid @RequestBody RoleAssignRequest request) {
        return R.ok(userManageService.assignRoles(id, request));
    }

    @PutMapping("/{id}/status")
    @RequireRoles(Constants.ROLE_ADMIN)
    public R<Void> updateStatus(@PathVariable("id") Long id,
                                @Valid @RequestBody StatusUpdateRequest request) {
        userManageService.updateStatus(id, request.getStatus());
        return R.ok();
    }

    @PutMapping("/{id}/password")
    @RequireRoles(Constants.ROLE_ADMIN)
    public R<Void> resetPassword(@PathVariable("id") Long id,
                                 @Valid @RequestBody ResetPasswordRequest request) {
        userManageService.resetPassword(id, request.getNewPassword());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRoles(Constants.ROLE_ADMIN)
    public R<Void> delete(@PathVariable("id") Long id) {
        userManageService.delete(id);
        return R.ok();
    }
}
