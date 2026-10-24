package com.kzai.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员视角的用户信息。
 */
@Data
@Builder
public class UserAdminVO {
    /** M15.17：初始密码（仅创建响应一次性返回） */
    private String initialPassword;


    private Long id;
    private String username;
    private String cellPhone;
    /** 0禁用，1正常 */
    private Integer status;
    /** 原始用户类型：1员工，2学员，3老师 */
    private Integer type;
    private String typeName;
    /** M2 扩展权限角色 */
    private List<String> roleCodes;
    private LocalDateTime createTime;
}
