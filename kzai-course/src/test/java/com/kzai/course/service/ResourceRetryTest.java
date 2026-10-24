package com.kzai.course.service;

import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.course.client.AigcKnowledgeClient;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseResource;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.mapper.CourseResourceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 教师重试只转发资源 ID，并拒绝其他教师、未审核和已撤回来源。 */
class ResourceRetryTest {

    private CourseResourceMapper resourceMapper;
    private CourseSupport courseSupport;
    private AigcKnowledgeClient client;
    private ResourceService service;

    @BeforeEach
    void setUp() {
        resourceMapper = mock(CourseResourceMapper.class);
        courseSupport = mock(CourseSupport.class);
        client = mock(AigcKnowledgeClient.class);
        service = new ResourceService(resourceMapper, mock(CourseMapper.class), courseSupport,
                mock(CategoryService.class), mock(FileStorageService.class), mock(TeacherCourseService.class),
                mock(ChapterService.class), mock(EntitlementProvider.class), client);
        UserContext.set(8L, "teacher", 2, List.of("TEACHER"), "r");
        when(courseSupport.requireOwned(7L)).thenReturn(new Course());
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void failedReviewedResourceRetriesWithoutASecondSubmit() {
        when(resourceMapper.selectById(3L)).thenReturn(resource(3L, 8L, 0, 1, 5));
        when(client.retry(any())).thenReturn(R.ok(Map.of(
                "jobId", "900", "phase", "INDEXING", "alreadyQueued", false, "accessLevel", "PROTECTED")));

        Map<String, Object> out = service.retryKb(3L);

        assertEquals("900", out.get("jobId"));
        assertEquals("INDEXING", out.get("phase"));
        assertEquals("PROTECTED", out.get("accessLevel"));
        verify(client).retry(Map.of("resourceId", "3"));
        verify(client, never()).submitKb(any());
    }

    @Test
    void otherTeacherPendingWithdrawnAndReplacedCannotRetry() {
        when(resourceMapper.selectById(3L)).thenReturn(resource(3L, 9L, 0, 1, 5));
        assertThrows(AuthException.class, () -> service.retryKb(3L));

        when(resourceMapper.selectById(3L)).thenReturn(resource(3L, 8L, 0, 0, 5));
        assertThrows(BizException.class, () -> service.retryKb(3L));

        when(resourceMapper.selectById(3L)).thenReturn(resource(3L, 8L, 0, 2, 5));
        assertThrows(BizException.class, () -> service.retryKb(3L));

        CourseResource replaced = resource(3L, 8L, 1, 1, 5);
        when(resourceMapper.selectById(3L)).thenReturn(replaced);
        assertThrows(BizException.class, () -> service.retryKb(3L));
        verify(client, never()).retry(any());
    }

    private static CourseResource resource(long id, long uploader, int deleted, int review, int index) {
        CourseResource resource = new CourseResource();
        resource.setId(id);
        resource.setCourseId(7L);
        resource.setUploader(uploader);
        resource.setDeleted(deleted);
        resource.setKbDocId(30L);
        resource.setKbStatus(1);
        resource.setKbReviewStatus(review);
        resource.setKbIndexStatus(index);
        resource.setFileName("a.md");
        return resource;
    }
}
