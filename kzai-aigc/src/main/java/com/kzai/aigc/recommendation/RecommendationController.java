package com.kzai.aigc.recommendation;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/chat/recommendations")
@RequiredArgsConstructor
public class RecommendationController {
    private final RecommendationService recommendations;
    private final RecommendationPreferences preferences;
    @GetMapping("/{sessionId}")
    public R<RecommendationRequest> preferences(@PathVariable String sessionId) {
        Long id=UserContext.getUserId();
        if(id==null)throw new AuthException(ResultCode.UNAUTHORIZED,"未登录");
        return R.ok(preferences.get(id,sessionId));
    }
    public record Query(@jakarta.validation.constraints.NotBlank String sessionId, @Valid @jakarta.validation.constraints.NotNull RecommendationRequest preferences){}
    @PostMapping
    public R<Map<String,Object>> recommend(@Valid @RequestBody Query query) {
        Long id=UserContext.getUserId();
        if(id==null)throw new AuthException(ResultCode.UNAUTHORIZED,"未登录");
        return R.ok(recommendations.recommend(id,query.sessionId(),query.preferences()));
    }
}
