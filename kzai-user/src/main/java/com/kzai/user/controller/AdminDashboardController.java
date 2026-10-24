package com.kzai.user.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.user.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** M13 管理员运营数据看板（/admin/dashboard → kzai-user；仅 ADMIN）。 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(defaultValue = "1") int days) {
        return R.ok(dashboardService.overview(days));
    }
}
