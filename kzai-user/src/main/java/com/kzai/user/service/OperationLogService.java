package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.user.entity.OperationLog;
import com.kzai.user.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 重要管理操作日志（M2 扩展表 sys_operation_log）。
 * 需求 3.2.5.2：用户管理的新增、编辑、角色变更、启停、重置密码、删除均需留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    /** M10：跨服务审计写入（内部接口调用方显式携带操作人身份，无 UserContext） */
    public void recordRaw(Long operatorId, String operatorName, String module, String operation,
                          String targetType, Long targetId, String detail) {
        OperationLog record = new OperationLog();
        record.setOperatorId(operatorId);
        record.setOperatorName(operatorName);
        record.setModule(module);
        record.setOperation(operation);
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setDetail(detail);
        try {
            operationLogMapper.insert(record);
        } catch (Exception e) {
            log.error("[op-log] persist failed: {}", e.getMessage());
        }
    }

    public void record(String module, String operation, String targetType, Long targetId, String detail) {
        OperationLog record = new OperationLog();
        record.setOperatorId(UserContext.getUserId());
        record.setOperatorName(UserContext.getUsername());
        record.setModule(module);
        record.setOperation(operation);
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setDetail(detail);
        try {
            operationLogMapper.insert(record);
        } catch (Exception e) {
            // 日志写入失败不阻断业务，但必须留痕到应用日志
            log.error("[op-log] persist failed: {}", e.getMessage());
        }
    }

    public void recordUser(String operation, Long targetId, String detail) {
        record(Constants.MODULE_USER, operation, "USER", targetId, detail);
    }
}
