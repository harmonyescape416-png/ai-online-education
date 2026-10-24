package com.kzai.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.BizException;
import com.kzai.user.entity.UserRole;
import com.kzai.user.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 权限角色（M2 扩展表 sys_user_role）。
 * <p>
 * 兼容性约定：角色为空时按原始 user.type 推导，保证未补数据的历史账号鉴权不受影响。
 */
@Service
@RequiredArgsConstructor
public class UserRoleService {

    private static final Set<String> ALLOWED = Set.of(
            Constants.ROLE_ADMIN, Constants.ROLE_STAFF,
            Constants.ROLE_TEACHER, Constants.ROLE_STUDENT);

    private final UserRoleMapper userRoleMapper;

    /** 校验角色编码合法性，非法直接抛业务异常（白名单校验同时阻断 SQL 注入） */
    public void validate(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BizException("角色不能为空");
        }
        for (String code : roleCodes) {
            if (code == null || !ALLOWED.contains(code)) {
                throw new BizException("角色编码非法：" + code);
            }
        }
    }

    public List<String> roleCodes(Long userId) {
        List<UserRole> roles = userRoleMapper.selectList(
                Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
        return roles.stream().map(UserRole::getRoleCode).distinct().toList();
    }

    /** 取角色，无记录时按 user.type 推导（历史账号兼容） */
    public List<String> resolve(Long userId, Integer type) {
        List<String> codes = roleCodes(userId);
        if (!codes.isEmpty()) {
            return codes;
        }
        return deriveFromType(type);
    }

    /**
     * 无角色记录时按 user.type 推导（历史账号兼容）。
     * <p>
     * 遗留修复①：type=1（员工）回退为 <b>STAFF</b> 而非 ADMIN——
     * ADMIN 具备全部后台管理权限，必须由 sys_user_role 显式授予，
     * 否则任何 type=1 的普通员工账号（如 seed 中的 ai_bot）都会被静默提权为管理员。
     */
    public static List<String> deriveFromType(Integer type) {
        if (type == null) {
            return List.of(Constants.ROLE_STUDENT);
        }
        return switch (type) {
            case Constants.USER_TYPE_STAFF -> List.of(Constants.ROLE_STAFF);
            case Constants.USER_TYPE_TEACHER -> List.of(Constants.ROLE_TEACHER);
            default -> List.of(Constants.ROLE_STUDENT);
        };
    }

    /** 由角色反推原始 user.type，保持 user.type 与角色一致（不篡改枚举含义） */
    public static Integer deriveType(List<String> roleCodes) {
        if (roleCodes.contains(Constants.ROLE_ADMIN) || roleCodes.contains(Constants.ROLE_STAFF)) {
            return Constants.USER_TYPE_STAFF;
        }
        if (roleCodes.contains(Constants.ROLE_TEACHER)) {
            return Constants.USER_TYPE_TEACHER;
        }
        return Constants.USER_TYPE_STUDENT;
    }

    public void replaceRoles(Long userId, List<String> roleCodes, Long operatorId) {
        userRoleMapper.delete(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
        for (String code : roleCodes) {
            UserRole role = new UserRole();
            role.setUserId(userId);
            role.setRoleCode(code);
            role.setCreater(operatorId);
            userRoleMapper.insert(role);
        }
    }

    public void deleteByUserId(Long userId) {
        userRoleMapper.delete(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
    }
}
