package com.kzai.aigc.eval;

import com.kzai.aigc.eval.service.EvalRefusalRules;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15.9 拒答规则 V2 专项：语义等价拒答识别（独立版本 REFUSAL_V2，不覆盖历史 V1 判定）。
 * 背景：M15.8 量子纠缠用例——模型如实说明「课程体系并未包含、知识库文档未提及」，
 * V1 判 NOT_REFUSED 漏判（人工复核为合理拒答）。V2 增补否认存在性话术族。
 */
class EvalRefusalRulesV2Test {

    private final EvalRefusalRules rules = new EvalRefusalRules();

    @Test
    void v2识别量子纠缠类合理拒答() {
        String answer = "目前课栈智伴平台的课程体系中，并未包含「量子纠缠技术」相关课程，"
                + "也未在现有知识库文档（如《M12评测·课程有效期与学习规则》）中提及该技术。";
        var v1 = rules.judge(answer, false, false, true);
        assertFalse(v1.refusal(), "V1 维持原判（历史口径不变）");
        var v2 = rules.judgeV2(answer, false, false, true);
        assertTrue(v2.refusal(), "V2 应识别语义等价拒答: " + v2.rule());
        assertTrue(v2.rule().startsWith("REFUSAL_V2:"), "V2 结果带版本前缀");
    }

    @Test
    void v1行为完全不变() {
        var v1 = rules.judge("无法回答该问题", false, false, true);
        assertTrue(v1.refusal() && "PHRASE:无法回答".equals(v1.rule()));
        var plain = rules.judge("平台有 Java 和 Python 课程", false, false, true);
        assertFalse("REFUSAL_V2:NOT_REFUSED".equals(plain.rule()), "judge() 为 V1 口径");
    }

    @Test
    void v2不误判普通课程回答() {
        String normal = "平台的退款政策为：开课前可无条件全额退款；开课后 7 天内可申请全额退款。";
        var v2 = rules.judgeV2(normal, false, false, true);
        assertFalse(v2.rule().contains("PHRASE:未包含") || v2.rule().contains("PHRASE:不包含"),
                "普通肯定式回答不命中否认存在性话术: " + v2.rule());
    }

    @Test
    void v2不影响GREETING正常回复() {
        var greeting = rules.judgeV2("你好，我是课栈智伴 AI 助手，有什么可以帮你？", false, true, false);
        assertFalse(greeting.refusal(), "GREETING 正常回复不判拒答");
        var shortOther = rules.judgeV2("OTHER", false, true, true);
        assertTrue(shortOther.refusal() && shortOther.rule().contains("OTHER_SHORT_DIRECT"));
    }

    @Test
    void 空回答两个版本均判拒答() {
        assertTrue(rules.judge("", true, false, true).refusal());
        assertTrue(rules.judgeV2("", true, false, true).refusal());
    }

    @Test
    void 引用与泄露判定不受V2影响() {
        Map<String, String> titles = Map.of("d1", "M12评测·平台退款政策");
        assertFalse(rules.hasCitation("无法回答", titles));
        assertTrue(rules.leakSuspected("依据《M12评测·平台退款政策》…", true, titles));
    }
}
