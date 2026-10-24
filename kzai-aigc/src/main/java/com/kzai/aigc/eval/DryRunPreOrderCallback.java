package com.kzai.aigc.eval;

import com.kzai.aigc.tools.OrderTools;
import com.kzai.aigc.tools.PreOrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.List;
import java.util.function.BiFunction;

/**
 * M15：BUY 类评测的<b>受控 Dry-run 预下单回调</b>（修复 M12 遗留缺口——
 * 此前评测路径不挂工具回调，「模型是否正确选择 BUY 写工具」无法验证）。
 *
 * <p><b>零副作用红线</b>：
 * <ul>
 *   <li>与真实 {@code OrderTools.createPreOrder} 同名同参（模型视角完全一致），但
 *       执行体只做三件事：记录工具名/参数 → 参数校验 → 返回受控拒绝文案；</li>
 *   <li><b>绝不调用 TradeClient</b>、不创建订单、不写幂等表、不触碰 PurchaseProposalStore；</li>
 *   <li>仅 REAL 模式 BUY 用例挂载（MOCK 由 MockToolScript 独立验证协议）；</li>
 *   <li>计数模型单元测试（EvalDryRunCallbackTest）已验证运行期回调可被框架真实执行。</li>
 * </ul>
 */
@Slf4j
public final class DryRunPreOrderCallback {

    /** 单条 Dry-run 记录 */
    public record DryRunSelection(String toolName, String courseId, String validation, String resultPreview) {
    }

    /**
     * 创建 Dry-run 回调。
     *
     * @param records 记录容器（由调用方持有，评测后写回 ai_eval_result.write_tool_attempt 与 answer 注记）
     */
    public static FunctionCallback create(List<DryRunSelection> records) {
        BiFunction<PreOrderRequest, org.springframework.ai.chat.model.ToolContext, String> fn =
                (req, ctx) -> {
                    String courseId = req == null ? null : req.getCourseId();
                    String validation;
                    if (req == null || courseId == null || courseId.isBlank()) {
                        validation = "INVALID_PARAMS:courseId 缺失";
                    } else if (!courseId.matches("\\d{1,19}")) {
                        validation = "INVALID_PARAMS:courseId 非法格式";
                    } else {
                        validation = "OK";
                    }
                    String result = "{\"dryRun\":true,\"message\":\"【评测 Dry-run】已记录模型工具选择，"
                            + "未执行任何下单操作。请向用户说明本次为评测流程。\"}";
                    records.add(new DryRunSelection(OrderTools.NAME_CREATE, courseId, validation, result));
                    log.info("[eval.dryrun] 模型选择 BUY 写工具（受控记录，不下单）: courseId={} validation={}",
                            courseId, validation);
                    return result;
                };
        return FunctionCallback.builder()
                .function(OrderTools.NAME_CREATE, fn)
                .description("为用户创建课程待支付订单（预下单）。调用前提：用户已明确确认购买该课程；"
                        + "courseId 为字符串数字。")
                .inputType(PreOrderRequest.class)
                .build();
    }

    /** 统计工具尝试次数（含校验失败） */
    public static int attempts(List<DryRunSelection> records) {
        return records.size();
    }

    /** 汇总记录为可入库摘要（answer 附加注记） */
    public static String summarize(List<DryRunSelection> records) {
        if (records.isEmpty()) {
            return "DRYRUN:模型未发起预下单工具调用";
        }
        StringBuilder sb = new StringBuilder("DRYRUN:");
        for (int i = 0; i < records.size(); i++) {
            DryRunSelection s = records.get(i);
            sb.append("#").append(i + 1).append(" tool=").append(s.toolName())
                    .append(" courseId=").append(s.courseId())
                    .append(" validation=").append(s.validation())
                    .append(i + 1 < records.size() ? "; " : "");
        }
        return sb.toString();
    }
}
