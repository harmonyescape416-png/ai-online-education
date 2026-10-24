package com.kzai.aigc.tools;

/**
 * searchCourses 工具入参（模型生成 JSON 反序列化目标）。
 * 模型只提供意图参数（关键词/数量上限）；身份、会话、价格等一律不由模型提供。
 */
public class CourseSearchRequest {

    private String keyword;
    private Integer limit;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
