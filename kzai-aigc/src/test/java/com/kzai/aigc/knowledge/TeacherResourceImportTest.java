package com.kzai.aigc.knowledge;

import com.kzai.aigc.client.CourseClient;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import com.kzai.aigc.knowledge.mapper.KnowledgeIndexJobMapper;
import com.kzai.aigc.supervision.OperationAuditBridge;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 教师资料入库：真实字节、SHA、访问级别和重复提交。
 * 不调用 Embedding，也不写 Elasticsearch，因此不能当成语义检索验收。
 */
class TeacherResourceImportTest {

    @TempDir
    Path dir;

    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeAdminService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        AiProperties props = new AiProperties();
        props.setKnowledgeStorageDir(dir.toString());
        props.setKnowledgeMaxFileSize(10 * 1024 * 1024);
        service = new KnowledgeAdminService(documentMapper, mock(KnowledgeIndexJobMapper.class), props,
                mock(CourseClient.class), mock(OperationAuditBridge.class));
    }

    @Test
    void twoDifferentFilesDoNotShareEmptyShaAndPaidCourseIsProtected() {
        CourseClient courseClient = clientOf(service);
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument doc = inv.getArgument(0);
            doc.setId(doc.getSourceResourceId());
            return 1;
        });
        when(courseClient.resourceContent(11L)).thenReturn(R.ok(payload(11L, 21L, false, "a.md", "# 课程一\n正文")));
        when(courseClient.resourceContent(12L)).thenReturn(R.ok(payload(12L, 22L, true, "b.md", "# 课程二\n另一份正文")));

        Map<String, Object> first = service.importTeacherResource(11L);
        Map<String, Object> second = service.importTeacherResource(12L);

        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentMapper, times(2)).insert(saved.capture());
        KnowledgeDocument paid = saved.getAllValues().get(0);
        KnowledgeDocument free = saved.getAllValues().get(1);
        assertEquals(64, paid.getSha256().length());
        assertEquals(64, free.getSha256().length());
        assertNotEquals(paid.getSha256(), free.getSha256());
        assertEquals(KnowledgeDocument.ACCESS_PROTECTED, paid.getAccessLevel());
        assertEquals(KnowledgeDocument.ACCESS_PUBLIC, free.getAccessLevel());
        assertEquals(KnowledgeDocument.TYPE_MARKDOWN, paid.getDocType());
        assertEquals("REVIEW_PENDING", first.get("phase"));
        assertEquals("REVIEW_PENDING", second.get("phase"));
    }

    @Test
    void sameContentInAnotherCourseKeepsSeparateAccess() {
        CourseClient courseClient = clientOf(service);
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(KnowledgeDocument.class))).thenReturn(1);
        String text = "# 同一份讲义\n权限必须按课程分开";
        when(courseClient.resourceContent(31L)).thenReturn(R.ok(payload(31L, 1L, false, "same.md", text)));
        when(courseClient.resourceContent(32L)).thenReturn(R.ok(payload(32L, 2L, true, "same.md", text)));

        service.importTeacherResource(31L);
        service.importTeacherResource(32L);

        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentMapper, times(2)).insert(saved.capture());
        assertEquals(saved.getAllValues().get(0).getSha256(), saved.getAllValues().get(1).getSha256());
        assertEquals(KnowledgeDocument.ACCESS_PROTECTED, saved.getAllValues().get(0).getAccessLevel());
        assertEquals(KnowledgeDocument.ACCESS_PUBLIC, saved.getAllValues().get(1).getAccessLevel());
        assertNotEquals(saved.getAllValues().get(0).getCourseId(), saved.getAllValues().get(1).getCourseId());
    }

    @Test
    void retryReturnsExistingDocumentWithoutSecondInsert() {
        KnowledgeDocument existing = new KnowledgeDocument();
        existing.setId(99L);
        existing.setSourceResourceId(11L);
        existing.setSourceVersion("11");
        existing.setReviewStatus(KnowledgeDocument.REVIEW_PENDING);
        existing.setIndexStatus(KnowledgeDocument.INDEX_PENDING);
        existing.setAccessLevel(KnowledgeDocument.ACCESS_PROTECTED);
        when(documentMapper.selectOne(any())).thenReturn(existing);

        Map<String, Object> again = service.importTeacherResource(11L);

        assertEquals("99", again.get("docId"));
        verify(documentMapper, times(0)).insert(any(KnowledgeDocument.class));
    }

    @Test
    void emptyMarkdownIsRejected() {
        CourseClient courseClient = clientOf(service);
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(courseClient.resourceContent(11L)).thenReturn(R.ok(payload(11L, 1L, true, "empty.md", "   \n")));
        BizException ex = assertThrows(BizException.class, () -> service.importTeacherResource(11L));
        assertTrue(ex.getMessage().contains("为空") || ex.getMessage().contains("没有可提取的正文"), ex.getMessage());
    }

    private static Map<String, Object> payload(long resourceId, long courseId, boolean free, String name, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceId", String.valueOf(resourceId));
        body.put("courseId", String.valueOf(courseId));
        body.put("free", free);
        body.put("fileName", name);
        body.put("uploaderId", "7");
        body.put("contentBase64", Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
        return body;
    }

    private static CourseClient clientOf(KnowledgeAdminService service) {
        try {
            var field = KnowledgeAdminService.class.getDeclaredField("courseClient");
            field.setAccessible(true);
            return (CourseClient) field.get(service);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
