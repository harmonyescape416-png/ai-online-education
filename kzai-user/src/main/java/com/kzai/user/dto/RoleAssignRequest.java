package com.kzai.user.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 角色分配（ADMIN / STAFF / TEACHER / STUDENT）。
 */
@Data
public class RoleAssignRequest {

    @NotEmpty(message = "角色不能为空")
    private List<String> roleCodes;
}
