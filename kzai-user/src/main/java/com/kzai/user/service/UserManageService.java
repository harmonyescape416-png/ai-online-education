package com.kzai.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.security.TokenVersionService;
import com.kzai.user.dto.RoleAssignRequest;
import com.kzai.user.dto.UserAdminQuery;
import com.kzai.user.dto.UserCreateRequest;
import com.kzai.user.dto.UserUpdateRequest;
import com.kzai.user.entity.User;
import com.kzai.user.mapper.UserMapper;
import com.kzai.user.vo.UserAdminVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员用户管理（需求 3.2.5.2）。
 * <p>
 * 角色与状态变更会调用 TokenVersionService 自增版本号，使目标用户全部已签发 token 立即失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserManageService {

    /** 管理员创建账号时的默认初始密码（首次登录后应提示修改） */
    public static final String DEFAULT_PASSWORD = "Kzai@123456";

    private final UserMapper userMapper;
    private final UserRoleService userRoleService;
    private final OperationLogService operationLogService;
    private final TokenVersionService tokenVersionService;
    private final List<UserDeletionGuard> deletionGuards;
    private final NotificationService notificationService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 严格删除开关（默认开启）：未接入校验实现的关联类型一律拒绝删除。
     * 仅在显式评估风险后才允许关闭。
     */
    @Value("${kzai.user.deletion.strict:true}")
    private boolean strictDeletion;

    /** 用户列表分页与组合条件搜索 */
    public IPage<UserAdminVO> page(UserAdminQuery query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 10 : query.getPageSize();

        var wrapper = Wrappers.<User>lambdaQuery()
                .like(StringUtils.hasText(query.getUsername()), User::getUsername, query.getUsername())
                .like(StringUtils.hasText(query.getCellPhone()), User::getCellPhone, query.getCellPhone())
                .eq(query.getType() != null, User::getType, query.getType())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus());
        if (StringUtils.hasText(query.getRoleCode())) {
            // 先白名单校验角色编码，杜绝拼接注入
            userRoleService.validate(List.of(query.getRoleCode()));
            wrapper.exists("SELECT 1 FROM sys_user_role ur WHERE ur.user_id = `user`.id "
                    + "AND ur.role_code = '" + query.getRoleCode() + "'");
        }
        wrapper.orderByDesc(User::getId);

        IPage<User> page = userMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return page.convert(this::toVO);
    }

    public UserAdminVO detail(Long id) {
        return toVO(getUserOrThrow(id));
    }

    /** 新增学员 / 教师账号（M15.17：禁止后台创建管理员——防越权提权；初始密码随机生成一次性返回） */
    public UserAdminVO create(UserCreateRequest request) {
        checkUsernameUnique(request.getUsername(), null);
        checkCellPhoneUnique(request.getCellPhone(), null);
        if (request.getType() == null
                || request.getType() < Constants.USER_TYPE_STUDENT
                || request.getType() > Constants.USER_TYPE_TEACHER) {
            throw new BizException("仅支持创建学员或教师账号");
        }

        List<String> roleCodes = request.getRoleCodes() == null || request.getRoleCodes().isEmpty()
                ? UserRoleService.deriveFromType(request.getType())
                : request.getRoleCodes();
        if (roleCodes.contains(Constants.ROLE_ADMIN)) {
            throw new BizException("不允许通过管理接口创建管理员账号");
        }
        userRoleService.validate(roleCodes);

        String initialPassword = StringUtils.hasText(request.getPassword())
                ? request.getPassword() : randomPassword();
        if (initialPassword.length() < 6 || initialPassword.length() > 32) {
            throw new BizException("初始密码长度需为 6-32 位");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setCellPhone(request.getCellPhone());
        user.setType(UserRoleService.deriveType(roleCodes));
        user.setStatus(1);
        user.setPassword(passwordEncoder.encode(initialPassword));
        user.setCreater(UserContext.getUserId());
        user.setUpdater(UserContext.getUserId());
        userMapper.insert(user);

        userRoleService.replaceRoles(user.getId(), roleCodes, UserContext.getUserId());
        operationLogService.recordUser(Constants.OP_CREATE, user.getId(),
                "新增账号 " + user.getUsername() + "，角色 " + roleCodes);
        UserAdminVO vo = toVO(user);
        vo.setInitialPassword(initialPassword);
        return vo;
    }

    private static final String PWD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%";

    private String randomPassword() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(PWD_ALPHABET.charAt(r.nextInt(PWD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private boolean existsUsername(String username) {
        return userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)) > 0;
    }

    private boolean existsCellPhone(String phone) {
        return userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getCellPhone, phone)) > 0;
    }

    private String trimOrNull(String v) {
        if (v == null) return null;
        String t = v.replace("\uFEFF", "").trim();
        return t.isEmpty() ? null : t;
    }

    /** 修改允许编辑的用户信息（手机号） */
    public UserAdminVO update(Long id, UserUpdateRequest request) {
        User user = getUserOrThrow(id);
        if (request.getCellPhone() != null) {
            checkCellPhoneUnique(request.getCellPhone(), id);
            user.setCellPhone(request.getCellPhone());
        }
        user.setUpdater(UserContext.getUserId());
        userMapper.updateById(user);
        operationLogService.recordUser(Constants.OP_UPDATE, id, "修改手机号 " + user.getCellPhone());
        return toVO(user);
    }

    /** 分配 / 调整角色，并同步原始 user.type 使两者保持一致 */
    public UserAdminVO assignRoles(Long id, RoleAssignRequest request) {
        User user = getUserOrThrow(id);
        userRoleService.validate(request.getRoleCodes());

        userRoleService.replaceRoles(id, request.getRoleCodes(), UserContext.getUserId());
        user.setType(UserRoleService.deriveType(request.getRoleCodes()));
        user.setUpdater(UserContext.getUserId());
        userMapper.updateById(user);

        // 角色变化后立即失效其全部 token，防止旧权限继续使用
        tokenVersionService.bump(id);
        operationLogService.recordUser(Constants.OP_ROLE, id, "角色调整为 " + request.getRoleCodes());
        return toVO(user);
    }

    /** 管理员重置密码 */
    public void resetPassword(Long id, String newPassword) {
        User user = getUserOrThrow(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setUpdater(UserContext.getUserId());
        userMapper.updateById(user);

        tokenVersionService.bump(id);
        operationLogService.recordUser(Constants.OP_RESET_PWD, id, "重置密码");
    }

    /**
     * M15.17：CSV 批量导入（≤500 行；行级校验；部分成功——合法行导入、错误行返回清单）。
     * 导入账号 status=1 直接启用，初始密码随机生成随结果一次性返回（管理员线下交付，无统一明文密码）。
     * 激活策略：导入手机号不视为已短信验证——首登用初始密码；「验证码首登激活」需持久化激活状态，另行批准。
     */
    public Map<String, Object> importCsv(List<String[]> rows, Long operatorId) {
        if (rows.size() > 500) {
            throw new BizException("单次导入不能超过 500 行");
        }
        List<Map<String, Object>> success = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> seenUsername = new HashSet<>();
        Set<String> seenPhone = new HashSet<>();
        int rowNo = 1;
        for (String[] row : rows) {
            rowNo++;
            String username = row.length > 0 ? trimOrNull(row[0]) : null;
            String phone = row.length > 1 ? trimOrNull(row[1]) : null;
            String typeStr = row.length > 2 ? trimOrNull(row[2]) : null;
            try {
                if (username == null) throw new BizException("用户名缺失");
                if (!username.matches("^[A-Za-z0-9_]{3,20}$")) throw new BizException("用户名需 3-20 位字母/数字/下划线");
                if (!seenUsername.add(username.toLowerCase()) || existsUsername(username)) {
                    throw new BizException("用户名已存在或文件内重复");
                }
                if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) throw new BizException("手机号格式不正确");
                if (!seenPhone.add(phone) || existsCellPhone(phone)) {
                    throw new BizException("手机号已存在或文件内重复");
                }
                int type;
                if ("STUDENT".equalsIgnoreCase(typeStr)) type = Constants.USER_TYPE_STUDENT;
                else if ("TEACHER".equalsIgnoreCase(typeStr)) type = Constants.USER_TYPE_TEACHER;
                else throw new BizException("角色仅支持 STUDENT/TEACHER（禁止导入 ADMIN）");
                List<String> roleCodes = UserRoleService.deriveFromType(type);
                String initialPassword = randomPassword();
                LocalDateTime now = LocalDateTime.now();
                User user = new User();
                user.setUsername(username);
                user.setCellPhone(phone);
                user.setType(type);
                user.setStatus(1);
                user.setPassword(passwordEncoder.encode(initialPassword));
                user.setCreateTime(now);
                user.setUpdateTime(now);
                user.setCreater(operatorId);
                user.setUpdater(operatorId);
                try {
                    userMapper.insert(user);
                } catch (DuplicateKeyException e) {
                    throw new BizException("用户名或手机号已被占用");
                }
                userRoleService.replaceRoles(user.getId(), roleCodes, operatorId);
                success.add(Map.of("row", rowNo, "username", username,
                        "initialPassword", initialPassword,
                        "role", type == Constants.USER_TYPE_TEACHER ? "教师" : "学员"));
            } catch (BizException e) {
                errors.add(Map.of("row", rowNo, "username", username == null ? "-" : username,
                        "reason", e.getMessage() == null ? "未知错误" : e.getMessage()));
            } catch (Exception e) {
                errors.add(Map.of("row", rowNo, "username", username == null ? "-" : username, "reason", "导入异常"));
            }
        }
        operationLogService.recordUser(Constants.OP_IMPORT, operatorId,
                "批量导入账号：成功 " + success.size() + " 行，失败 " + errors.size() + " 行");
        return Map.of("successCount", success.size(), "errorCount", errors.size(),
                "accounts", success, "errors", errors);
    }

    /** 启用 / 禁用账号；禁用后旧 token 立即失效 */
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("状态非法");
        }
        User user = getUserOrThrow(id);
        Long operatorId = UserContext.getUserId();
        if (status == 0 && operatorId != null && operatorId.equals(id)) {
            throw new BizException("不能禁用当前登录账号");
        }
        user.setStatus(status);
        user.setUpdater(operatorId);
        userMapper.updateById(user);

        if (status == 0) {
            tokenVersionService.bump(id);
        }
        operationLogService.recordUser(Constants.OP_STATUS, id, "状态变更为 " + (status == 1 ? "启用" : "禁用"));
    }

    /**
     * 删除用户：先做关联业务校验，再删除账号与角色。
     * <p>
     * M2 遗留修复③：12 张核心表中与 user 直接关联的业务类型必须<b>全部</b>有校验实现；
     * 缺少任何一类（对应业务服务尚未建设，无法确认是否存在数据）即拒绝删除，
     * 不假设"服务未建设 = 没有业务数据"。
     * <p>
     * M10：INTERACTION Guard（InteractionAssociationGuard）补齐后，
     * {@link Constants#REQUIRED_DELETION_BINDINGS} 五类全部有实现。
     */
    /** 当前已接入的关联校验实现（测试可裁剪以模拟"未接入"，便于验证 fail-closed） */
    public List<UserDeletionGuard> getDeletionGuards() {
        return deletionGuards;
    }

    public void delete(Long id) {
        User user = getUserOrThrow(id);
        if (strictDeletion) {
            Set<String> covered = deletionGuards.stream()
                    .map(UserDeletionGuard::bindingType)
                    .collect(Collectors.toSet());
            for (String required : Constants.REQUIRED_DELETION_BINDINGS) {
                if (!covered.contains(required)) {
                    throw new BizException("关联类型[" + required + "]的校验服务尚未接入，暂不允许删除该用户");
                }
            }
        }
        for (UserDeletionGuard guard : deletionGuards) {
            long count = guard.count(id);
            if (count > 0) {
                throw new BizException("该用户存在关联" + guard.bindingName() + "数据（" + count + " 条），无法删除");
            }
            if (count < 0) {
                throw new BizException(guard.bindingName() + "关联校验服务不可用，暂不允许删除");
            }
        }
        userRoleService.deleteByUserId(id);
        userMapper.deleteById(id);
        tokenVersionService.bump(id);
        // M11：通知归属本用户，随账号一并清理（避免新增通知表产生未处理的用户关联残留）
        notificationService.deleteByUser(id);
        operationLogService.recordUser(Constants.OP_DELETE, id, "删除账号 " + user.getUsername());
    }

    private User getUserOrThrow(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return user;
    }

    private void checkUsernameUnique(String username, Long excludeId) {
        if (!StringUtils.hasText(username)) {
            throw new BizException("登录账号不能为空");
        }
        User exist = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username).last("LIMIT 1"));
        if (exist != null && (excludeId == null || !exist.getId().equals(excludeId))) {
            throw new BizException("登录账号已存在");
        }
    }

    private void checkCellPhoneUnique(String cellPhone, Long excludeId) {
        if (!StringUtils.hasText(cellPhone)) {
            return;
        }
        User exist = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getCellPhone, cellPhone).last("LIMIT 1"));
        if (exist != null && (excludeId == null || !exist.getId().equals(excludeId))) {
            throw new BizException("手机号已被占用");
        }
    }

    private UserAdminVO toVO(User user) {
        return UserAdminVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .cellPhone(user.getCellPhone())
                .status(user.getStatus())
                .type(user.getType())
                .typeName(typeName(user.getType()))
                .roleCodes(userRoleService.resolve(user.getId(), user.getType()))
                .createTime(user.getCreateTime())
                .build();
    }

    private String typeName(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case Constants.USER_TYPE_STAFF -> "员工/管理员";
            case Constants.USER_TYPE_STUDENT -> "学员";
            case Constants.USER_TYPE_TEACHER -> "教师";
            default -> "未知";
        };
    }
}
