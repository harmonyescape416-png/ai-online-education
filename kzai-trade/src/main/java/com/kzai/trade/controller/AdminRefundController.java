package com.kzai.trade.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.trade.payment.RefundAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/refunds")
@RequireRoles({Constants.ROLE_ADMIN, Constants.ROLE_STAFF})
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundAppService refunds;

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(refunds.adminList());
    }

    @PostMapping("/{id}/approve")
    public R<Map<String, Object>> approve(@PathVariable("id") Long id) {
        return R.ok(refunds.approveAndExecute(UserContext.getUserId(), id));
    }

    @PostMapping("/{id}/reject")
    public R<Map<String, Object>> reject(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return R.ok(refunds.reject(UserContext.getUserId(), id, reason));
    }
}
