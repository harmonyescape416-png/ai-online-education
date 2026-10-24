package com.kzai.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * course 表映射（数据库设计说明书 3.3.3，20 个字段）。
 */
@Data
@TableName("`course`")
public class Course {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 类型：1直播，2录播 */
    private Integer courseType;

    private String coverUrl;

    private Long firstCateId;

    private Long secondCateId;

    private Long thirdCateId;

    /** 0付费，1免费 */
    private Integer free;

    /** 课程价格，单位分 */
    private Integer price;

    /** 1待上架，2已上架，3下架，4已完结 */
    private Integer status;

    private LocalDateTime purchaseStartTime;

    private LocalDateTime purchaseEndTime;

    /** 有效期，单位月 */
    private Integer validDuration;

    private Integer sectionNum;

    /** 评分，45代表4.5星 */
    private Integer score;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    /** 逻辑删除：0正常，1删除 */
    @TableLogic
    private Integer deleted;
}
