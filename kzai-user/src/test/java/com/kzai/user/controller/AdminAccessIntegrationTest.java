package com.kzai.user.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.security.InternalAuthUtils;
import com.kzai.common.security.TokenVersionService;
import com.kzai.user.client.CourseClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后端接口级角色鉴权集成测试（MockMvc 走真实拦截器链，不是 Mock 替代）。
 * 验证：不能只依赖前端隐藏按钮，服务端必须拦截越权请求。
 * <p>
 * 请求按网关方式携带内部签名头（遗留修复④：无签名的身份头会被丢弃）。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:admin_access_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.sql.init.data-locations=classpath:data-test.sql",
        "spring.cloud.nacos.discovery.enabled=false"
})
@AutoConfigureMockMvc
class AdminAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${kzai.internal.secret}")
    private String internalSecret;

    @MockBean
    private CourseClient courseClient;

    @MockBean
    private TokenVersionService tokenVersionService;

    /** 模拟网关注入身份：userId/username/role/roles + 内部签名 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(String roles,
                                                                                        String uri) {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder = get(uri)
                .header(Constants.HEADER_USER_ID, "3")
                .header(Constants.HEADER_USERNAME, "student01")
                .header(Constants.HEADER_USER_ROLE, String.valueOf(Constants.USER_TYPE_STUDENT))
                .header(Constants.HEADER_USER_ROLES, roles);
        return withSignature(builder, "3", "student01",
                String.valueOf(Constants.USER_TYPE_STUDENT), roles);
    }

    private <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T withSignature(
            T builder, String userId, String username, String role, String roles) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        builder.header(Constants.HEADER_REQUEST_ID, requestId);
        builder.header(Constants.HEADER_INTERNAL_SIGN, InternalAuthUtils.sign(internalSecret,
                InternalAuthUtils.payload(requestId, userId, username, role, roles)));
        return builder;
    }

    @Test
    void adminCanAccessUserList() throws Exception {
        mockMvc.perform(as(Constants.ROLE_ADMIN, "/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void studentCannotAccessUserList() throws Exception {
        mockMvc.perform(as(Constants.ROLE_STUDENT, "/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void teacherCannotAccessUserList() throws Exception {
        mockMvc.perform(as(Constants.ROLE_TEACHER, "/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void studentCannotCreateUser() throws Exception {
        mockMvc.perform(withSignature((org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) post("/admin/users")
                        .header(Constants.HEADER_USER_ID, "3")
                        .header(Constants.HEADER_USERNAME, "student01")
                        .header(Constants.HEADER_USER_ROLES, Constants.ROLE_STUDENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hack\",\"cellPhone\":\"13700000000\",\"type\":1}"),
                "3", "student01", "", Constants.ROLE_STUDENT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void anonymousRequestRejected() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void forgedIdentityHeadersWithoutSignatureAreIgnored() throws Exception {
        // 遗留修复④：绕过网关直连业务服务时，伪造的身份头无合法签名，必须按匿名处理
        mockMvc.perform(get("/admin/users")
                        .header(Constants.HEADER_USER_ID, "1")
                        .header(Constants.HEADER_USERNAME, "admin")
                        .header(Constants.HEADER_USER_ROLES, Constants.ROLE_ADMIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
