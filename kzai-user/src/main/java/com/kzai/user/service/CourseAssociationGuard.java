package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.user.client.CourseClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 课程关联校验：course.creater 指向该用户时禁止删除（课程服务提供内部统计接口）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseAssociationGuard implements UserDeletionGuard {

    private final CourseClient courseClient;

    @Override
    public String bindingName() {
        return "课程";
    }

    @Override
    public String bindingType() {
        return Constants.ASSOC_COURSE;
    }

    @Override
    public long count(Long userId) {
        try {
            R<Long> result = courseClient.countByCreator(userId);
            Long count = result == null ? null : result.getData();
            return count == null ? 0L : count;
        } catch (Exception e) {
            // 校验服务不可用时返回负数，由上层保守拒绝删除，避免误删数据
            log.warn("[user] course association check unavailable: {}", e.getMessage());
            return -1L;
        }
    }
}
