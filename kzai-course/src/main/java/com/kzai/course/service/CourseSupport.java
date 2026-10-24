package com.kzai.course.service;

import com.kzai.common.context.UserContext;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.ResultCode;
import com.kzai.course.entity.Course;
import com.kzai.course.mapper.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 课程归属与状态流转的公共支撑（M3）。
 * <p>
 * 需求：教师只能操作本人负责的课程；管理员可代为处理但需留痕。
 */
@Component
@RequiredArgsConstructor
public class CourseSupport {

    private final CourseMapper courseMapper;

    /** 当前用户是否具备后台管理角色 */
    public static boolean isBackstageAdmin() {
        return UserContext.hasRole(Constants.ROLE_ADMIN) || UserContext.hasRole(Constants.ROLE_STAFF);
    }

    /** 取课程并校验归属：非管理员且非创建人直接 403 */
    public Course requireOwned(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || Integer.valueOf(1).equals(course.getDeleted())) {
            throw new BizException("课程不存在");
        }
        if (!isBackstageAdmin() && !Objects.equals(course.getCreater(), UserContext.getUserId())) {
            throw new AuthException(ResultCode.FORBIDDEN, "只能操作本人负责的课程");
        }
        return course;
    }

    /** 校验课程状态流转合法性 */
    public static void checkTransition(int from, int to) {
        boolean legal = switch (to) {
            case Constants.COURSE_STATUS_PENDING -> from == Constants.COURSE_STATUS_PENDING
                    || from == Constants.COURSE_STATUS_OFFLINE;
            case Constants.COURSE_STATUS_ONLINE -> from == Constants.COURSE_STATUS_PENDING;
            case Constants.COURSE_STATUS_OFFLINE -> from == Constants.COURSE_STATUS_ONLINE;
            case Constants.COURSE_STATUS_FINISHED -> from == Constants.COURSE_STATUS_ONLINE;
            default -> false;
        };
        if (!legal) {
            throw new BizException("非法的课程状态流转：" + statusText(from) + " → " + statusText(to));
        }
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Constants.COURSE_STATUS_PENDING -> "待上架";
            case Constants.COURSE_STATUS_ONLINE -> "已上架";
            case Constants.COURSE_STATUS_OFFLINE -> "已下架";
            case Constants.COURSE_STATUS_FINISHED -> "已完结";
            default -> "未知";
        };
    }

    public static String auditStatusText(Integer auditStatus) {
        if (auditStatus == null) {
            return "未提交";
        }
        return switch (auditStatus) {
            case Constants.AUDIT_PENDING -> "待审核";
            case Constants.AUDIT_APPROVED -> "审核通过";
            case Constants.AUDIT_REJECTED -> "已驳回";
            case Constants.AUDIT_OFFLINE -> "强制下架";
            default -> "未知";
        };
    }

    /** 判断是否处于「已发布可见」状态：已上架 */
    public static boolean visibleToStudent(Integer status) {
        return Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(status);
    }

    public static List<Long> emptyIfNull(List<Long> ids) {
        return ids == null ? List.of() : ids;
    }
}
