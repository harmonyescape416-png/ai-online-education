package com.kzai.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.BizException;
import com.kzai.user.dto.UserDTO;
import com.kzai.user.entity.User;
import com.kzai.user.mapper.UserMapper;
import com.kzai.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserRoleService userRoleService;

    /**
     * 当前登录用户个人信息（GET /users/me）。
     */
    public UserVO getById(Long userId) {
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return toVO(user, userRoleService.resolve(userId, user.getType()));
    }

    /**
     * 按登录账号查询账号信息（供 auth 服务校验密码，服务间调用）。
     */
    public UserDTO findByUsername(String username) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username).last("LIMIT 1"));
        if (user == null) {
            return null;
        }
        UserDTO dto = new UserDTO();
        BeanUtils.copyProperties(user, dto);
        dto.setRoles(userRoleService.resolve(user.getId(), user.getType()));
        return dto;
    }

    /** M15.17：按手机号查询账号（手机号+密码登录支持；同一 DTO 结构） */
    public UserDTO findDtoByPhone(String phone) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getCellPhone, phone).last("LIMIT 1"));
        if (user == null) {
            return null;
        }
        UserDTO dto = new UserDTO();
        BeanUtils.copyProperties(user, dto);
        dto.setRoles(userRoleService.resolve(user.getId(), user.getType()));
        return dto;
    }

    /**
     * 按用户ID批量取账号名（M5 内部接口：学情统计展示学员）。
     * 只返回 id → username，不返回手机号等敏感字段。
     */
    public Map<Long, String> namesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectList(
                Wrappers.<User>lambdaQuery().in(User::getId, distinct));
        return users.stream().collect(java.util.stream.Collectors.toMap(
                User::getId, u -> u.getUsername() == null ? "" : u.getUsername(), (a, b) -> a));
    }

    public static UserVO toVO(User user, List<String> roles) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .cellPhone(user.getCellPhone())
                .status(user.getStatus())
                .type(user.getType())
                .roleName(roleName(user.getType()))
                .roles(roles)
                .createTime(user.getCreateTime())
                .build();
    }

    private static String roleName(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case Constants.USER_TYPE_STAFF -> "管理员";
            case Constants.USER_TYPE_STUDENT -> "学员";
            case Constants.USER_TYPE_TEACHER -> "教师";
            default -> "未知";
        };
    }
}
