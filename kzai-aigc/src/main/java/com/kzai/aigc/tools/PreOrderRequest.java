package com.kzai.aigc.tools;

/**
 * createPreOrder 工具入参（模型生成 JSON 反序列化目标）。
 * <p>
 * 只有 courseId 一个字段：userId 一律来自 ToolContext 的可信身份；
 * requestId 由服务端从「用户确认过的购买提案」派生；价格由交易服务实时计算。
 * 模型无法指定用户身份、价格或订单状态。
 */
public class PreOrderRequest {

    private String courseId;

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }
}
