package com.kzai.user.service;

import com.kzai.common.exception.BizException;
import com.kzai.user.dto.UserDTO;
import com.kzai.user.vo.UserVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户服务集成测试：H2（MySQL 兼容模式）真实执行 SQL，校验 user 表访问与 VO 装配。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:user_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Test
    void getById_returnsProfileWithoutPassword() {
        UserVO vo = userService.getById(3L);

        assertEquals("student01", vo.getUsername());
        assertEquals("13800000003", vo.getCellPhone());
        assertEquals(2, vo.getType());
        assertEquals("学员", vo.getRoleName());
        assertNotNull(vo.getCreateTime());
    }

    @Test
    void getById_roleNameMapping() {
        assertEquals("管理员", userService.getById(1L).getRoleName());
        assertEquals("教师", userService.getById(2L).getRoleName());
    }

    @Test
    void getById_notFound_throwsBizException() {
        assertThrows(BizException.class, () -> userService.getById(999999L));
    }

    @Test
    void getById_nullUserId_throwsBizException() {
        BizException ex = assertThrows(BizException.class, () -> userService.getById(null));
        assertEquals(401, ex.getCode());
    }

    @Test
    void findByUsername_returnsCredentialForAuthService() {
        UserDTO dto = userService.findByUsername("teacher01");

        assertNotNull(dto);
        assertEquals(2L, dto.getId());
        assertEquals(3, dto.getType());
        assertNotNull(dto.getPassword(), "auth 服务需要密码哈希做 BCrypt 比对");
    }

    @Test
    void findByUsername_notExists_returnsNull() {
        assertNull(userService.findByUsername("ghost"));
    }
}
