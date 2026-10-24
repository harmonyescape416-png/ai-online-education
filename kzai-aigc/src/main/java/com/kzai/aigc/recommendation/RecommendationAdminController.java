package com.kzai.aigc.recommendation;
import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/ai/recommendations")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
@ConditionalOnProperty(name="kzai.aigc.recommendation-indexing-enabled", havingValue="true")
public class RecommendationAdminController {
    private final RecommendationSync sync;
    @GetMapping public R<Map<String,Object>> status(){return R.ok(sync.status());}
    @PostMapping("/retry") public R<Integer> retry(){return R.ok(sync.retryFailed());}
    @PostMapping("/rebuild") public R<Integer> rebuild(){return R.ok(sync.rebuild());}
}
