package com.kzai.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 个人信息返回体，不返回密码（需求分析说明书 3.2.5.2 个人中心）。
 */
@Data
@Builder
public class UserVO {

    private Long id;
    private String username;
    private String cellPhone;
    /** 0禁用，1正常 */
    private Integer status;
    /** 1员工，2学员，3老师 */
    private Integer type;
    private String roleName;
    /** M2 扩展：权限角色编码，供前端动态菜单使用 */
    private List<String> roles;
    private LocalDateTime createTime;
    /** M15.15 方案 A：扩展资料（昵称/头像） */
    private String nickname;
    private String avatarUrl;
}
