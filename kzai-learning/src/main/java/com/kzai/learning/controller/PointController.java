package com.kzai.learning.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.learning.points.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/learning")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class PointController {

    private final PointService points;

    @PostMapping("/checkins")
    public R<Map<String, Object>> checkin() {
        return R.ok(points.checkin(userId()));
    }

    @GetMapping("/checkins/today")
    public R<Map<String, Object>> today() {
        return R.ok(points.today(userId()));
    }

    @GetMapping("/checkins")
    public R<Map<String, Object>> month(@RequestParam(value = "month", required = false) String month) {
        return R.ok(points.month(userId(), month));
    }

    @GetMapping("/points/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(points.summary(userId()));
    }

    @GetMapping("/points/ledger")
    public R<Map<String, Object>> ledger(@RequestParam(value = "pageNum", defaultValue = "1") long page,
                                         @RequestParam(value = "pageSize", defaultValue = "10") long size) {
        return R.ok(points.ledger(userId(), page, size));
    }

    @GetMapping("/ranks")
    public R<Map<String, Object>> ranks(@RequestParam(value = "period", required = false) String period,
                                        @RequestParam(value = "pageNum", defaultValue = "1") long page,
                                        @RequestParam(value = "pageSize", defaultValue = "20") long size) {
        return R.ok(points.rank(period, page, size, userId()));
    }

    private long userId() {
        Long id = UserContext.getUserId();
        if (id == null) {
            throw new AuthException("未登录");
        }
        return id;
    }
}
