package com.kzai.auth.client.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户服务内部返回的账号信息（含密码哈希，仅服务间调用，网关不暴露 /internal/**）。
 */
@Data
public class UserDTO implements Serializable {

    private Long id;
    private String username;
    private String cellPhone;
    /** BCrypt 密码哈希 */
    private String password;
    /** 状态：0禁用，1正常 */
    private Integer status;
    /** 类型：1员工，2学员，3老师（原始枚举，保持不变） */
    private Integer type;
    /** M2 扩展：权限角色编码，为空时由 auth 服务按 type 推导 */
    private List<String> roles;
    private LocalDateTime createTime;
}
