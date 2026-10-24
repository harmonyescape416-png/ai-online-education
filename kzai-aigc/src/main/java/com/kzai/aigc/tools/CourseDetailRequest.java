package com.kzai.aigc.tools;

/**
 * getCourseDetail 工具入参（模型生成 JSON 反序列化目标）。
 * courseId 按字符串传输（防 Long 精度丢失），由工具侧校验为数字。
 */
public class CourseDetailRequest {

    private String courseId;

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }
}
