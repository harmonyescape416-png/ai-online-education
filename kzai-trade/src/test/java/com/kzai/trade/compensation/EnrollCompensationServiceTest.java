package com.kzai.trade.compensation;

import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.trade.client.InternalLearningClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M15.20-C 回归：开课补偿（登记幂等/状态转换/退避/人工重试权限/ObjectProvider Feign）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrollCompensationServiceTest {

    @Mock
    private EnrollCompensationMapper mapper;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // MP lambdaUpdate 需要实体 TableInfo 缓存（单测环境无 Spring 初始化）
        com.baomidou.mybatisplus.core.MybatisConfiguration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(cfg, ""),
                EnrollCompensationEntity.class);
    }

    @Mock
    private org.springframework.beans.factory.ObjectProvider<InternalLearningClient> provider;

    @Mock
    private InternalLearningClient learningClient;

    @InjectMocks
    private EnrollCompensationService service;

    private EnrollCompensationEntity comp(Long id, Long orderId, Long courseId, int attempts, String status) {
        EnrollCompensationEntity e = new EnrollCompensationEntity();
        e.setId(id);
        e.setOrderId(orderId);
        e.setCourseId(courseId);
        e.setUserId(9L);
        e.setAttempts(attempts);
        e.setStatus(status);
        return e;
    }

    @Test
    void manualRetry_ownerAllowed_successUpdatesStatus() {
        var c = comp(1L, 88L, 101L, 3, "FAILED_MANUAL");
        when(mapper.selectList(any())).thenReturn(List.of(c));
        when(mapper.update(any(), any())).thenReturn(1); // 认领成功
        when(provider.getObject()).thenReturn(learningClient);
        when(learningClient.enrollForPaid(any())).thenReturn(R.ok(Map.of("lessonId", "1")));

        service.manualRetry(9L, 88L, false);

        // manualRetry 先置 PENDING 再 doEnroll——updateById 调用 2 次，最终状态 SUCCESS
        ArgumentCaptor<EnrollCompensationEntity> captor = ArgumentCaptor.forClass(EnrollCompensationEntity.class);
        verify(mapper, atLeastOnce()).updateById(captor.capture());
        assertEquals("SUCCESS", captor.getValue().getStatus());
    }

    @Test
    void manualRetry_nonOwnerDenied() {
        when(mapper.selectList(any())).thenReturn(List.of(comp(1L, 88L, 101L, 3, "FAILED_MANUAL")));
        assertThrows(BizException.class, () -> service.manualRetry(777L, 88L, false));
    }

    @Test
    void manualRetry_successSkipped_notReEnrolled() {
        var success = comp(1L, 88L, 101L, 1, "SUCCESS");
        when(mapper.selectList(any())).thenReturn(List.of(success));
        service.manualRetry(9L, 88L, true);
        assertEquals("SUCCESS", success.getStatus());
        assertEquals(1, success.getAttempts());
        verify(mapper, never()).updateById(any(EnrollCompensationEntity.class));
    }

    @Test
    void doEnroll_threeFailures_failedManual_withBackoff() {
        var c = comp(1L, 88L, 101L, 3, "PENDING");
        when(mapper.selectList(any())).thenReturn(List.of(c));
        when(mapper.update(any(), any())).thenReturn(1); // 认领成功
        when(provider.getObject()).thenReturn(learningClient);
        when(learningClient.enrollForPaid(any())).thenThrow(new RuntimeException("learning down"));

        service.executeNow(9L, 88L);

        assertEquals("FAILED_MANUAL", c.getStatus());
        assertNotNull(c.getLastError());
        assertTrue(c.getNextRetryAt() != null);
    }

    @Test
    void registerPending_duplicateSkipped() {
        when(mapper.selectCount(any())).thenReturn(1L);
        service.registerPending(9L, 88L, List.of(101L));
        verify(mapper, never()).insert(any(EnrollCompensationEntity.class));
    }
}
