package com.kzai.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.course.entity.Course;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CourseMapper extends BaseMapper<Course> {

    /** 包括逻辑删除行，只提供 ID，推荐同步端需要为旧索引写不可见标记。 */
    @Select("SELECT id FROM course WHERE id > #{after} ORDER BY id LIMIT #{limit}")
    java.util.List<Long> recommendationIds(@Param("after") long after, @Param("limit") int limit);

    /**
     * 关键词搜索：课程名称 + 课程简介/适用人群（需求分析说明书 3.2.3.1）。
     * 仅返回已上架（status=2）且未逻辑删除的课程。
     */
    @Select("""
            SELECT c.* FROM `course` c
            WHERE c.deleted = 0 AND c.status = 2
              AND (
                c.name LIKE CONCAT('%', #{kw}, '%')
                OR EXISTS (
                  SELECT 1 FROM course_content cc
                  WHERE cc.id = c.id AND cc.deleted = 0
                    AND (cc.course_introduce LIKE CONCAT('%', #{kw}, '%')
                         OR cc.use_people LIKE CONCAT('%', #{kw}, '%'))
                )
              )
            ORDER BY c.create_time DESC
            """)
    IPage<Course> searchPage(Page<?> page, @Param("kw") String kw);
}
