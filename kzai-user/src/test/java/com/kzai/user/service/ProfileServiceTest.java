package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.security.TokenVersionService;
import com.kzai.user.client.CourseClient;
import com.kzai.user.dto.ChangePasswordRequest;
import com.kzai.user.dto.ProfileUpdateRequest;
import com.kzai.user.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * 个人中心集成测试（H2 真实 SQL）。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:profile_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
@Transactional
class ProfileServiceTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private com.kzai.user.mapper.UserMapper userMapper;

    @MockBean
    private CourseClient courseClient;

    @MockBean
    private TokenVersionService tokenVersionService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        UserContext.set(3L, "student01", Constants.USER_TYPE_STUDENT, List.of(Constants.ROLE_STUDENT), "test");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void updateProfile_changesCellPhone() {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setCellPhone("13800000123");

        UserVO vo = profileService.updateProfile(request);

        assertEquals("13800000123", vo.getCellPhone());
        assertEquals("13800000123", userMapper.selectById(3L).getCellPhone());
    }

    @Test
    void updateProfile_duplicateCellPhoneRejected() {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setCellPhone("13800000002");

        BizException ex = assertThrows(BizException.class, () -> profileService.updateProfile(request));
        assertEquals("手机号已被占用", ex.getMessage());
    }

    @Test
    void changePassword_wrongOldPasswordRejected() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("wrong-one");
        request.setNewPassword("NewPass@123");

        BizException ex = assertThrows(BizException.class, () -> profileService.changePassword(request));
        assertEquals("原密码不正确", ex.getMessage());
    }

    @Test
    void changePassword_sameAsOldRejected() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("student123");
        request.setNewPassword("student123");

        BizException ex = assertThrows(BizException.class, () -> profileService.changePassword(request));
        assertEquals("新密码不能与原密码相同", ex.getMessage());
    }

    @Test
    void changePassword_successAndInvalidatesOldToken() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("student123");
        request.setNewPassword("Changed@123");

        profileService.changePassword(request);

        assertTrue(encoder.matches("Changed@123", userMapper.selectById(3L).getPassword()));
        verify(tokenVersionService).bump(3L);
    }
}
