package com.kzai.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用 / 禁用账号。status：0禁用，1正常。
 */
@Data
public class StatusUpdateRequest {

    @NotNull(message = "状态不能为空")
    private Integer status;
}
