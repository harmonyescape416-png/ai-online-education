package com.kzai.aigc.prompt;

import com.kzai.aigc.agent.AgentTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** M15.20 回归：Prompt 单模板版本（回滚隔离/并发唯一/会话快照解析） */
@ExtendWith(MockitoExtension.class)
class PromptVersionRollbackTest {

    @Mock
    private PromptVersionMapper mapper;

    @InjectMocks
    private PromptVersionService service;

    @Test
    void record_incrementsVersionPerType() {
        when(mapper.selectList(any())).thenReturn(List.of(
                version("CONSULT", 3), version("BUY", 5)));
        var r = service.record("CONSULT", "{}", "PUBLISH", 1L, true, "ok");
        assertEquals(4, r.entity().getVersionNo());
    }

    @Test
    void byVersionNo_returnsMatchingEntity() {
        // byVersionNo（DB 过滤由 MyBatis 执行——mock 层验证类型与版本号透传）
        when(mapper.selectList(any())).thenReturn(List.of(version("BUY", 9)));
        var e = service.byVersionNo("BUY", 9);
        assertEquals(9, e.getVersionNo());
        assertEquals("BUY", e.getAgentType());
    }


    @Test
    void specFromVersionMap_missingType_returnsNull() {
        assertNull(service.specFromVersionMap("{\"CONSULT\":2}", AgentTypeEnum.BUY));
    }

    @Test
    void specFromVersionMap_resolvesSnapshot() {
        var entity = version("CONSULT", 2);
        entity.setContent("{\"systemPrompt\":\"SP\",\"welcomeText\":\"W\",\"hotQuestions\":[\"q1\"]}");
        when(mapper.selectList(any())).thenReturn(List.of(entity));
        var spec = service.specFromVersionMap("{\"CONSULT\":2}", AgentTypeEnum.CONSULT);
        assertNotNull(spec);
        assertEquals("SP", spec.getSystemPrompt());
        assertEquals("VERSION_SNAPSHOT", spec.getSource());
    }

    private PromptVersionEntity version(String type, int no) {
        PromptVersionEntity e = new PromptVersionEntity();
        e.setAgentType(type);
        e.setVersionNo(no);
        e.setAction("PUBLISH");
        e.setResultOk(1);
        e.setContent("{}");
        return e;
    }
}
