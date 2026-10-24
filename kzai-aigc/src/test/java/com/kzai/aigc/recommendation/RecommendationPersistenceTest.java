package com.kzai.aigc.recommendation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.client.CourseClient;
import com.kzai.aigc.session.ChatSessionService;
import com.kzai.common.result.R;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecommendationPersistenceTest {
    JdbcTemplate db;TransactionTemplate tx;RecommendationSync sync;CourseClient courses;RecommendationIndex index;
    final RecommendationIndex.Space space=new RecommendationIndex.Space("kzai-course-recommend-it",2,"MOCK","fixture",true);
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:recommend_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("recommendation-schema.sql")).execute(ds);
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        courses=mock(CourseClient.class);index=mock(RecommendationIndex.class);when(index.space()).thenReturn(space);
        sync=new RecommendationSync(db,tx,courses,index,new ObjectMapper());
    }
    Map<String,Object> row(String name,boolean visible) {
        var out=new LinkedHashMap<String,Object>();out.put("courseId","11");out.put("visible",visible);
        if(visible)out.put("course",Map.of("name",name,"introduce","public","usePeople","零基础","chapters",List.of()));return out;
    }
    void source(Map<String,Object> row){when(courses.recommendationSource(anyLong(),anyInt())).thenReturn(R.ok(List.of(row)));}
    @Test void duplicateSyncDoesNotReindexAndChangesIncrementRevision() {
        source(row("a",true));sync.syncPage();db.update("UPDATE ai_course_recommend_sync SET cursor_id=0");sync.syncPage();
        assertEquals(1L,db.queryForObject("SELECT revision FROM ai_course_recommend_job",Long.class));
        source(row("b",true));db.update("UPDATE ai_course_recommend_sync SET cursor_id=0");sync.syncPage();
        assertEquals(2L,db.queryForObject("SELECT revision FROM ai_course_recommend_job",Long.class));
    }
    @Test void failureRetriesAndExhaustionNeedsManualRetry() {
        source(row("a",true));sync.syncPage();doThrow(new IllegalStateException("ES down")).when(index).write(any(),anyLong(),anyLong(),anyMap());
        for(int i=0;i<5;i++){db.update("UPDATE ai_course_recommend_job SET next_retry_time=NOW()");sync.runBatch();}
        assertEquals("FAILED",db.queryForObject("SELECT status FROM ai_course_recommend_job",String.class));assertEquals(1,sync.retryFailed());
        doNothing().when(index).write(any(),anyLong(),anyLong(),anyMap());sync.runBatch();assertEquals("READY",db.queryForObject("SELECT status FROM ai_course_recommend_job",String.class));
    }
    @Test void oldWorkerCannotMarkNewRevisionSuccessful() {
        source(row("a",true));sync.syncPage();doAnswer(i->{db.update("UPDATE ai_course_recommend_job SET revision=2,status='PENDING',lease_owner=NULL");return null;}).when(index).write(any(),anyLong(),anyLong(),anyMap());
        sync.runBatch();assertEquals("PENDING",db.queryForObject("SELECT status FROM ai_course_recommend_job",String.class));
    }
    @Test void deletedSourceGetsTombstoneAfterFullCycle() {
        source(row("a",true));sync.syncPage();when(courses.recommendationSource(anyLong(),anyInt())).thenReturn(R.ok(List.of()));
        sync.syncPage();sync.syncPage();
        assertTrue(db.queryForObject("SELECT payload FROM ai_course_recommend_job",String.class).contains("\"visible\":false"));
        assertEquals(2L,db.queryForObject("SELECT revision FROM ai_course_recommend_job",Long.class));
    }
    @Test void preferencesAreSessionScopedAndCanClearBudget() {
        var sessions=mock(ChatSessionService.class);var p=new RecommendationPreferences(db,tx,new ObjectMapper(),sessions);
        var r=new RecommendationRequest();r.setGoal("Java");r.setBudgetCents(10000);p.merge(3,"a",r);
        assertNull(p.get(3,"b").getGoal());assertNull(p.get(4,"a").getGoal());
        var patch=new RecommendationRequest();patch.setClearBudget(true);assertNull(p.merge(3,"a",patch).getBudgetCents());assertEquals("Java",p.get(3,"a").getGoal());
        patch=new RecommendationRequest();patch.setHoursPerWeek(java.math.BigDecimal.TEN);patch.setWeeks(4);p.merge(3,"a",patch);
        patch=new RecommendationRequest();patch.setClearTime(true);patch.setWeeks(2);
        var updated=p.merge(3,"a",patch);assertNull(updated.getHoursPerWeek());assertEquals(2,updated.getWeeks());
        doThrow(new IllegalStateException("forbidden")).when(sessions).requireOwned(4,"a");
        assertThrows(IllegalStateException.class,()->p.get(4,"a"));
    }
    @Test void schemaIsIdempotentAndRebuildFencesRunningJobs() {
        source(row("a",true));sync.syncPage();assertEquals(1,sync.rebuild());
        assertEquals(2L,db.queryForObject("SELECT revision FROM ai_course_recommend_job",Long.class));assertEquals("PENDING",db.queryForObject("SELECT status FROM ai_course_recommend_job",String.class));
    }
    @Test void sessionCleanupOnlyDeletesTheMatchingUsersPreferences() {
        var p=new RecommendationPreferences(db,tx,new ObjectMapper(),mock(ChatSessionService.class));
        p.merge(3,"a",new RecommendationRequest());p.merge(3,"b",new RecommendationRequest());p.merge(4,"a",new RecommendationRequest());
        new RecommendationProfileCleanup(db).onDelete(new ChatSessionService.Deleted(3,"a"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ai_course_recommend_profile",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_course_recommend_profile WHERE user_id=3 AND session_id='a'",Integer.class));
    }
}
