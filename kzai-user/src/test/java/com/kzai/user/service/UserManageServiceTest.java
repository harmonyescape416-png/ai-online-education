package com.kzai.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.security.TokenVersionService;
import com.kzai.user.client.CourseClient;
import com.kzai.user.client.TradeClient;
import com.kzai.user.dto.RoleAssignRequest;
import com.kzai.user.dto.UserAdminQuery;
import com.kzai.user.dto.UserCreateRequest;
import com.kzai.user.dto.UserUpdateRequest;
import com.kzai.user.vo.UserAdminVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员用户管理集成测试：H2 真实 SQL，课程关联与 token 版本用 Mock 隔离外部依赖。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:user_manage_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
// 每个用例回滚，避免用例之间互相污染（同一 H2 库）
@Transactional
class UserManageServiceTest {

    @Autowired
    private UserManageService userManageService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private com.kzai.user.mapper.OperationLogMapper operationLogMapper;

    @Autowired
    private com.kzai.user.mapper.UserMapper userMapper;

    @Autowired
    private UserRoleService userRoleService;

    @MockBean
    private CourseClient courseClient;

    /** M4：交易服务内部接口桩（订单关联校验） */
    @MockBean
    private TradeClient tradeClient;

    /** M5：学习服务内部接口桩（学习记录关联校验） */
    @MockBean
    private com.kzai.user.client.LearningClient learningClient;

    /** M6：AI 服务内部接口桩（会话关联校验） */
    @MockBean
    private com.kzai.user.client.AigcClient aigcClient;

    @MockBean
    private TokenVersionService tokenVersionService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        UserContext.set(1L, "admin", Constants.USER_TYPE_STAFF, List.of(Constants.ROLE_ADMIN), "test");
        // M6：默认无 AI 会话，避免会话 Guard 因桩未打而走 fail-closed，干扰其它删除用例
        when(aigcClient.countSessionsByUser(anyLong()))
                .thenReturn(R.ok(Map.of("userId", "0", "count", 0L)));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void page_withCombinedConditions() {
        UserAdminQuery query = new UserAdminQuery();
        query.setPageNum(1);
        query.setPageSize(2);

        IPage<UserAdminVO> page = userManageService.page(query);

        assertEquals(4, page.getTotal());
        assertEquals(2, page.getPages());
        assertEquals(2, page.getRecords().size());
    }

    @Test
    void page_filterByUsername() {
        UserAdminQuery query = new UserAdminQuery();
        query.setUsername("student");
        query.setPageSize(10);

        IPage<UserAdminVO> page = userManageService.page(query);

        assertEquals(1, page.getTotal());
        assertEquals("student01", page.getRecords().get(0).getUsername());
    }

    @Test
    void create_assignsRolesAndDefaultPassword() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("brand_new");
        request.setCellPhone("13700000999");
        request.setType(Constants.USER_TYPE_TEACHER);
        request.setRoleCodes(List.of(Constants.ROLE_TEACHER));

        UserAdminVO created = userManageService.create(request);

        assertNotNull(created.getId());
        assertEquals(Constants.USER_TYPE_TEACHER, created.getType());
        assertEquals(List.of(Constants.ROLE_TEACHER), created.getRoleCodes());
        // M15.17：初始密码随机生成（非公开统一密码）——校验 BCrypt 可匹配且响应携带一次性明文
        assertTrue(encoder.matches(created.getInitialPassword(), passwordOf(created.getId())));
        assertNotEquals(UserManageService.DEFAULT_PASSWORD, created.getInitialPassword());
    }

    @Test
    void create_duplicateUsernameRejected() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("student01");
        request.setCellPhone("13700000998");
        request.setType(Constants.USER_TYPE_STUDENT);

        BizException ex = assertThrows(BizException.class, () -> userManageService.create(request));
        assertEquals("登录账号已存在", ex.getMessage());
    }

    @Test
    void create_duplicateCellPhoneRejected() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("another");
        request.setCellPhone("13800000003");
        request.setType(Constants.USER_TYPE_STUDENT);

        BizException ex = assertThrows(BizException.class, () -> userManageService.create(request));
        assertEquals("手机号已被占用", ex.getMessage());
    }

    @Test
    void update_editableFieldsOnly() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setCellPhone("13811112222");

        UserAdminVO updated = userManageService.update(3L, request);

        assertEquals("13811112222", updated.getCellPhone());
        // 账号与类型不应被本接口修改
        assertEquals("student01", updated.getUsername());
        assertEquals(Constants.USER_TYPE_STUDENT, updated.getType());
    }

    @Test
    void assignRoles_syncsUserTypeAndInvalidatesToken() {
        RoleAssignRequest request = new RoleAssignRequest();
        request.setRoleCodes(List.of(Constants.ROLE_TEACHER));

        UserAdminVO updated = userManageService.assignRoles(3L, request);

        assertEquals(List.of(Constants.ROLE_TEACHER), updated.getRoleCodes());
        assertEquals(Constants.USER_TYPE_TEACHER, updated.getType(), "角色变更后需同步原始 user.type");
        verify(tokenVersionService).bump(3L);
    }

    @Test
    void assignRoles_illegalRoleRejected() {
        RoleAssignRequest request = new RoleAssignRequest();
        request.setRoleCodes(List.of("HACKER"));

        assertThrows(BizException.class, () -> userManageService.assignRoles(3L, request));
    }

    @Test
    void resetPassword_updatesHashAndInvalidatesToken() {
        userManageService.resetPassword(3L, "NewPass@123");

        assertTrue(encoder.matches("NewPass@123", passwordOf(3L)));
        verify(tokenVersionService).bump(3L);
    }

    @Test
    void updateStatus_disablingInvalidatesToken() {
        userManageService.updateStatus(3L, 0);

        assertEquals(0, userManageService.detail(3L).getStatus());
        verify(tokenVersionService).bump(3L);
    }

    @Test
    void updateStatus_cannotDisableSelf() {
        BizException ex = assertThrows(BizException.class, () -> userManageService.updateStatus(1L, 0));
        assertEquals("不能禁用当前登录账号", ex.getMessage());
    }

    @Test
    void delete_blockedWhenCourseAssociationExists() {
        // 关闭严格模式，单独验证「课程 guard 返回关联数」这一层的行为
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(courseClient.countByCreator(anyLong())).thenReturn(R.ok(6L));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(2L));
        assertTrue(ex.getMessage().contains("关联课程"));
    }

    @Test
    void delete_blockedWhenOrderAssociationExists() {
        // M4：用户存在订单时禁止删除（订单数据由交易服务提供，用户服务不直连订单表）
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(learningClient.countLearningByUser(anyLong())).thenReturn(R.ok(0L));
        when(tradeClient.countOrdersByUser(anyLong())).thenReturn(R.ok(3L));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(3L));
        assertTrue(ex.getMessage().contains("关联订单"), ex.getMessage());
    }

    @Test
    void delete_blockedWhenOrderGuardUnavailable() {
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(learningClient.countLearningByUser(anyLong())).thenReturn(R.ok(0L));
        when(tradeClient.countOrdersByUser(anyLong())).thenThrow(new IllegalStateException("trade service down"));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(3L));
        assertTrue(ex.getMessage().contains("订单"), ex.getMessage());
    }

    @Test
    void delete_blockedWhenLearningAssociationExists() {
        // M5：用户存在学习记录时禁止删除（学习数据由学习服务提供，用户服务不直连学习表）
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(learningClient.countLearningByUser(anyLong())).thenReturn(R.ok(2L));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(3L));
        assertTrue(ex.getMessage().contains("关联学习记录"), ex.getMessage());
    }

    @Test
    void delete_blockedWhenLearningGuardUnavailable() {
        // 学习服务不可用时同样保守拒绝删除
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(learningClient.countLearningByUser(anyLong()))
                .thenThrow(new IllegalStateException("learning service down"));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(3L));
        assertTrue(ex.getMessage().contains("学习记录"), ex.getMessage());
    }

    @Test
    void delete_blockedWhenGuardUnavailable() {
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(courseClient.countByCreator(anyLong())).thenThrow(new IllegalStateException("course service down"));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(4L));
        assertTrue(ex.getMessage().contains("校验服务不可用"));
    }

    @Test
    void delete_rejectedWhenRequiredGuardMissing() {
        // 严格策略下的 fail-closed：任一必需关联类型缺少 Guard 实现即拒绝删除
        ReflectionTestUtils.setField(userManageService, "strictDeletion", true);
        when(courseClient.countByCreator(anyLong())).thenReturn(R.ok(0L));
        // 人为移除互动 Guard，模拟"该类业务校验尚未接入"场景
        ReflectionTestUtils.setField(userManageService, "deletionGuards",
                guardsWithout(Constants.ASSOC_INTERACTION));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(4L));
        assertTrue(ex.getMessage().contains("尚未接入"), ex.getMessage());
    }

    @Test
    void delete_allowedWhenAllRequiredGuardsCovered() {
        // M10：INTERACTION Guard 补齐后，严格模式下五类 Guard 齐全且计数为 0 时允许删除
        ReflectionTestUtils.setField(userManageService, "strictDeletion", true);
        when(courseClient.countByCreator(anyLong())).thenReturn(R.ok(0L));
        when(tradeClient.countOrdersByUser(anyLong())).thenReturn(R.ok(0L));
        when(learningClient.countLearningByUser(anyLong())).thenReturn(R.ok(0L));
        when(learningClient.countInteractionsByUser(anyLong())).thenReturn(R.ok(0L));
        when(aigcClient.countSessionsByUser(anyLong())).thenReturn(R.ok(java.util.Map.of("count", 0)));

        assertDoesNotThrow(() -> userManageService.delete(4L));
    }

    @Test
    void delete_blockedWhenInteractionAssociationExists() {
        // M10：存在课程提问/回答（含 AI 系统回复归属）时禁止删除
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(courseClient.countByCreator(anyLong())).thenReturn(R.ok(0L));
        when(learningClient.countLearningByUser(anyLong())).thenReturn(R.ok(0L));
        when(learningClient.countInteractionsByUser(anyLong())).thenReturn(R.ok(3L));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(4L));
        assertTrue(ex.getMessage().contains("课程问答互动"), ex.getMessage());
    }

    @Test
    void delete_blockedWhenInteractionGuardUnavailable() {
        // 互动校验服务不可用 → fail-closed 拒绝删除
        ReflectionTestUtils.setField(userManageService, "strictDeletion", false);
        when(learningClient.countLearningByUser(anyLong())).thenReturn(R.ok(0L));
        when(learningClient.countInteractionsByUser(anyLong()))
                .thenThrow(new IllegalStateException("learning service down"));

        BizException ex = assertThrows(BizException.class, () -> userManageService.delete(4L));
        assertTrue(ex.getMessage().contains("课程问答互动"), ex.getMessage());
    }

    /** 取当前 Guard 列表中排除指定关联类型后的子集（用于模拟"未接入"） */
    private List<UserDeletionGuard> guardsWithout(String bindingType) {
        return userManageService.getDeletionGuards().stream()
                .filter(g -> !bindingType.equals(g.bindingType()))
                .toList();
    }

    @Test
    void delete_succeedsWhenAllGuardsPass() {
        // 测试专用：补齐全部必需关联类型的校验实现，模拟各业务服务全部建成后的场景
        List<UserDeletionGuard> guards = new ArrayList<>();
        guards.add(stubGuard(Constants.ASSOC_COURSE, 0L));
        guards.add(stubGuard(Constants.ASSOC_ORDER, 0L));
        guards.add(stubGuard(Constants.ASSOC_LEARNING, 0L));
        guards.add(stubGuard(Constants.ASSOC_CHAT_SESSION, 0L));
        guards.add(stubGuard(Constants.ASSOC_INTERACTION, 0L));

        UserManageService service = new UserManageService(
                userMapper, userRoleService, operationLogService, tokenVersionService, guards,
                org.mockito.Mockito.mock(NotificationService.class));
        ReflectionTestUtils.setField(service, "strictDeletion", true);

        service.delete(4L);

        assertThrows(BizException.class, () -> userManageService.detail(4L));
        verify(tokenVersionService).bump(4L);
    }

    private static UserDeletionGuard stubGuard(String type, long count) {
        UserDeletionGuard guard = Mockito.mock(UserDeletionGuard.class);
        Mockito.when(guard.bindingType()).thenReturn(type);
        Mockito.when(guard.bindingName()).thenReturn(type);
        Mockito.when(guard.count(ArgumentMatchers.anyLong())).thenReturn(count);
        return guard;
    }

    @Test
    void operationLog_isRecorded() {
        long before = operationLogMapper.selectCount(null);

        userManageService.resetPassword(3L, "LogTest@123");

        long after = operationLogMapper.selectCount(null);
        assertEquals(before + 1, after, "重置密码需写入操作日志");
    }

    private String passwordOf(Long userId) {
        return userMapper.selectById(userId).getPassword();
    }
}
