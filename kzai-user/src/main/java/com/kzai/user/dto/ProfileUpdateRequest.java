package com.kzai.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人中心：修改允许编辑的个人资料（原始 user 表仅手机号可编辑，姓名等字段原表不具备）。
 */
@Data
public class ProfileUpdateRequest {

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String cellPhone;

    /** M15.15 方案 A：昵称（扩展表 user_profile_ext） */
    @Size(max = 30, message = "昵称最长 30 字")
    private String nickname;

    /** M15.15 方案 A：头像地址（由 /users/me/avatar 上传接口生成，仅接收本服务路径防伪造） */
    @Size(max = 500, message = "头像地址过长")
    private String avatarUrl;
}
