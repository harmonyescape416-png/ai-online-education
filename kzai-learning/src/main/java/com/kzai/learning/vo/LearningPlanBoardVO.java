package com.kzai.learning.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 本周学习计划摘要。周界为 Asia/Shanghai 的 [周一, 下周一)。 */
@Data
public class LearningPlanBoardVO {

    private String weekStart;
    private String weekEndExclusive;
    private String timezone;
    private String summary;
    private String summaryText;
    private String coverage;
    private String coverageText;
    private List<LearningPlanItemVO> plans = new ArrayList<>();
}
