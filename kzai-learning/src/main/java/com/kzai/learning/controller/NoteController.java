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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 学员笔记。身份只取登录上下文。 */
@RestController
@RequestMapping("/learning/notes")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(noteService.create(userId(), longValue(body, "courseId"), longValue(body, "chapterId"),
                text(body, "title"), text(body, "content"), intValue(body, "videoPositionSec"), text(body, "visibility")));
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return R.ok(noteService.update(userId(), id, longValue(body, "chapterId"),
                text(body, "title"), text(body, "content"), intValue(body, "videoPositionSec"),
                body != null && body.containsKey("videoPositionSec"), text(body, "visibility")));
    }

    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable("id") Long id) {
        noteService.remove(userId(), id);
        return R.ok();
    }

    @GetMapping("/mine")
    public R<Map<String, Object>> mine(@RequestParam(value = "courseId", required = false) Long courseId,
                                       @RequestParam(value = "keyword", required = false) String keyword,
                                       @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
                                       @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return R.ok(noteService.mine(userId(), courseId, keyword, pageNum, pageSize));
    }

    @GetMapping("/public")
    public R<Map<String, Object>> publicList(@RequestParam("courseId") Long courseId,
                                              @RequestParam(value = "chapterId", required = false) Long chapterId,
                                              @RequestParam(value = "sort", defaultValue = "time") String sort,
                                              @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
                                              @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return R.ok(noteService.publicList(userId(), courseId, chapterId, sort, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return R.ok(noteService.detail(userId(), id));
    }

    @PostMapping("/{id}/like")
    public R<Map<String, Object>> like(@PathVariable("id") Long id) {
        return R.ok(noteService.like(userId(), id));
    }

    @DeleteMapping("/{id}/like")
    public R<Map<String, Object>> unlike(@PathVariable("id") Long id) {
        return R.ok(noteService.unlike(userId(), id));
    }

    @GetMapping("/{id}/comments")
    public R<Map<String, Object>> comments(@PathVariable("id") Long id,
                                           @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
                                           @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return R.ok(noteService.comments(userId(), id, pageNum, pageSize));
    }

    @PostMapping("/{id}/comments")
    public R<Map<String, Object>> comment(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return R.ok(noteService.comment(userId(), id, text(body, "content")));
    }

    @DeleteMapping("/{id}/comments/{commentId}")
    public R<Void> removeComment(@PathVariable("id") Long id, @PathVariable("commentId") Long commentId) {
        noteService.removeComment(userId(), id, commentId);
        return R.ok();
    }

    private Long userId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }

    private static Long longValue(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new BizException("课程或章节不正确");
        }
    }

    private static Integer intValue(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new BizException("播放时间点不正确");
        }
    }
}
