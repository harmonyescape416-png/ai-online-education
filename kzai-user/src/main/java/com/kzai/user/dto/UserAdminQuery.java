package com.kzai.user.dto;

import lombok.Data;

/**
 * 管理员用户列表组合查询条件（需求分析说明书 3.2.5.2 用户管理）。
 */
@Data
public class UserAdminQuery {

    private Integer pageNum = 1;
    private Integer pageSize = 10;

    /** 登录账号模糊匹配 */
    private String username;

    /** 手机号模糊匹配 */
    private String cellPhone;

    /** 用户类型：1员工，2学员，3老师 */
    private Integer type;

    /** 权限角色编码：ADMIN/STAFF/TEACHER/STUDENT */
    private String roleCode;

    /** 状态：0禁用，1正常 */
    private Integer status;
}
