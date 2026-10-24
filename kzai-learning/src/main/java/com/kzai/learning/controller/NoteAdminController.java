package com.kzai.learning.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.note.NoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理员处理笔记。必须填写原因，并写入操作审计。 */
@RestController
@RequestMapping("/admin/notes")
@RequireRoles({Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class NoteAdminController {

    private final NoteService noteService;

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        noteService.adminDelete(operatorId(), id, reason(body));
        return R.ok();
    }

    @PutMapping("/{id}/visibility")
    public R<Map<String, Object>> hide(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return R.ok(noteService.adminHide(operatorId(), id, reason(body)));
    }

    private Long operatorId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    private static String reason(Map<String, Object> body) {
        if (body == null || body.get("reason") == null) {
            throw new BizException("请填写原因");
        }
        return String.valueOf(body.get("reason"));
    }
}
