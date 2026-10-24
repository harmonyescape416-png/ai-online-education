package com.kzai.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务间账号信息（含密码哈希，仅 /internal/** 暴露，网关不路由）。
 */
@Data
public class UserDTO implements Serializable {

    private Long id;
    private String username;
    private String cellPhone;
    private String password;
    /** 0禁用，1正常 */
    private Integer status;
    /** 1员工，2学员，3老师 */
    private Integer type;
    /** M2 扩展：权限角色编码，供 auth 服务写入 token */
    private List<String> roles;
    private LocalDateTime createTime;
}
