package com.kzai.learning.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.points.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin/points")
@RequireRoles({Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class PointAdminController {

    private final PointService points;

    @GetMapping("/rules")
    public R<java.util.List<Map<String, Object>>> rules() {
        return R.ok(points.rules());
    }

    @PutMapping("/rules/{source}")
    public R<Map<String, Object>> update(@PathVariable("source") String source, @RequestBody Map<String, Object> body) {
        return R.ok(points.updateRule(userId(), source,
                Integer.parseInt(String.valueOf(body.get("points"))),
                Integer.parseInt(String.valueOf(body.get("dailyCap"))),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", true)))));
    }

    @PostMapping("/adjust")
    public R<Map<String, Object>> adjust(@RequestBody Map<String, Object> body) {
        return R.ok(points.adjust(userId(),
                Long.parseLong(String.valueOf(body.get("userId"))),
                Integer.parseInt(String.valueOf(body.get("points"))),
                body.get("reason") == null ? "" : String.valueOf(body.get("reason")),
                body.get("requestId") == null ? "" : String.valueOf(body.get("requestId"))));
    }

    @PostMapping("/ranks/{period}/settle")
    public R<Map<String, Object>> settle(@PathVariable("period") String period) {
        return R.ok(points.settle(period));
    }

    private long userId() {
        Long id = UserContext.getUserId();
        if (id == null) {
            throw new AuthException("未登录");
        }
        return id;
    }
}
