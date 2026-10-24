package com.kzai.aigc.recommendation;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** 意图参数；没有 userId、可信价格或下单参数。null 保留会话已有条件，clear* 显式撤销可选条件。 */
@Data
public class RecommendationRequest {
    @Size(max=500) private String goal;
    @Size(max=100) private String keyword;
    @Pattern(regexp="BEGINNER|INTERMEDIATE|ANY") private String foundation;
    @Min(0) @Max(100000000) private Integer budgetCents;
    @DecimalMin("0.5") @DecimalMax("168") private BigDecimal hoursPerWeek;
    @Min(1) @Max(520) private Integer weeks;
    @Pattern(regexp="[0-9]{1,20}") private String categoryId;
    @Min(1) @Max(10) private Integer limit;
    private Boolean includeOwned;
    private boolean clearBudget;
    private boolean clearTime;
    private boolean clearCategory;
}
