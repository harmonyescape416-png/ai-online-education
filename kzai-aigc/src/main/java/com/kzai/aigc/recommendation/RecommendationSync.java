package com.kzai.aigc.recommendation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.kzai.aigc.client.CourseClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 全量分页对账补偿 + 每课程最新版本任务。扫描游标锁在同一个事务内，跨实例不会倒序提交快照。 */
@Slf4j
@Component
@ConditionalOnProperty(name="kzai.aigc.recommendation-indexing-enabled", havingValue="true")
public class RecommendationSync {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final CourseClient courses;
    private final RecommendationIndex index;
    private final ObjectMapper json;
    public RecommendationSync(JdbcTemplate db, TransactionTemplate tx, CourseClient courses, RecommendationIndex index, ObjectMapper json) {
        this.db=db;this.tx=tx;this.courses=courses;this.index=index;
        this.json=json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    }
    @Scheduled(scheduler="recommendationScheduler", fixedDelayString="${kzai.aigc.recommendation-sync-ms:5000}", initialDelay=30000)
    public void scan() {
        try { syncPage(); } catch(Exception e) { log.warn("[course-recommend] 目录同步失败，将在下个周期重试: {}", e.getMessage()); }
    }
    public void syncPage() {
        String namespace=index.space().name();
        tx.executeWithoutResult(status -> {
            db.update("INSERT IGNORE INTO ai_course_recommend_sync(namespace,cursor_id,cycle_no,update_time) VALUES(?,0,1,NOW(3))",namespace);
            var state=db.queryForMap("SELECT cursor_id,cycle_no FROM ai_course_recommend_sync WHERE namespace=? FOR UPDATE",namespace);
            long cursor=((Number)state.get("cursor_id")).longValue(), cycle=((Number)state.get("cycle_no")).longValue();
            var result=courses.recommendationSource(cursor,50);
            if(result==null || !result.isSuccess() || result.getData()==null) throw new IllegalStateException("课程目录不可用");
            var rows=result.getData();
            long last=cursor;
            for(var row:rows) {
                long id=Long.parseLong(String.valueOf(row.get("courseId")));
                if(id<=last) throw new IllegalStateException("课程目录分页顺序异常");
                last=id;
                save(namespace,id,cycle,row);
            }
            if(rows.isEmpty()) {
                // 物理删除的课程不再出现在源列表，完整一轮扫描结束后也必须产生墓碑。
                for(var old:db.queryForList("SELECT course_id FROM ai_course_recommend_job WHERE namespace=? AND last_seen_cycle<?",namespace,cycle)) {
                    long id=((Number)old.get("course_id")).longValue();
                    save(namespace,id,cycle,Map.of("courseId",String.valueOf(id),"visible",false));
                }
                db.update("UPDATE ai_course_recommend_sync SET cursor_id=0,cycle_no=cycle_no+1,update_time=NOW(3) WHERE namespace=?",namespace);
            } else db.update("UPDATE ai_course_recommend_sync SET cursor_id=?,update_time=NOW(3) WHERE namespace=?",last,namespace);
        });
    }
    private void save(String ns,long id,long cycle,Map<String,Object> row) {
        String payload;
        try {payload=json.writeValueAsString(row);}catch(Exception e){throw new IllegalStateException(e);}
        String hash=RecommendationIndex.hash(payload);
        var old=db.queryForList("SELECT fingerprint FROM ai_course_recommend_job WHERE namespace=? AND course_id=? FOR UPDATE",ns,id);
        if(old.isEmpty()) db.update("""
            INSERT INTO ai_course_recommend_job(namespace,course_id,revision,fingerprint,payload,last_seen_cycle,status,attempt,next_retry_time,update_time)
            VALUES(?,?,1,?,?,?,'PENDING',0,NOW(3),NOW(3))
            """,ns,id,hash,payload,cycle);
        else if(!hash.equals(old.get(0).get("fingerprint"))) db.update("""
            UPDATE ai_course_recommend_job SET revision=revision+1,fingerprint=?,payload=?,last_seen_cycle=?,status='PENDING',attempt=0,
            lease_owner=NULL,lease_until=NULL,next_retry_time=NOW(3),last_error=NULL,update_time=NOW(3) WHERE namespace=? AND course_id=?
            """,hash,payload,cycle,ns,id);
        else db.update("UPDATE ai_course_recommend_job SET last_seen_cycle=? WHERE namespace=? AND course_id=?",cycle,ns,id);
    }
    @Scheduled(scheduler="recommendationScheduler", fixedDelayString="${kzai.aigc.recommendation-worker-ms:1000}", initialDelay=32000)
    public void work() {
        try { runBatch(); } catch(Exception e){log.warn("[course-recommend] 索引任务暂不可用: {}",e.getMessage());}
    }
    public void runBatch() {
        var space=index.space();
        var rows=db.queryForList("""
            SELECT * FROM ai_course_recommend_job WHERE namespace=? AND
            ((status='PENDING' AND next_retry_time<=NOW(3)) OR (status='RUNNING' AND lease_until<NOW(3)))
            ORDER BY update_time,course_id LIMIT 3
            """,space.name());
        for(var row:rows) {
            long id=((Number)row.get("course_id")).longValue(), rev=((Number)row.get("revision")).longValue();
            String owner=UUID.randomUUID().toString();
            int claimed=db.update("""
                UPDATE ai_course_recommend_job SET status='RUNNING',lease_owner=?,lease_until=?,update_time=NOW(3)
                WHERE namespace=? AND course_id=? AND revision=? AND
                ((status='PENDING' AND next_retry_time<=NOW(3)) OR (status='RUNNING' AND lease_until<NOW(3)))
                """,owner,LocalDateTime.now().plusMinutes(2),space.name(),id,rev);
            if(claimed!=1) continue;
            try {
                index.ensure(space);
                index.write(space,id,rev,json.readValue(String.valueOf(row.get("payload")),new TypeReference<>(){}));
                db.update("""
                    UPDATE ai_course_recommend_job SET status='READY',lease_owner=NULL,lease_until=NULL,last_error=NULL,update_time=NOW(3)
                    WHERE namespace=? AND course_id=? AND revision=? AND lease_owner=?
                    """,space.name(),id,rev,owner);
            } catch(Exception e) {
                int attempt=((Number)row.get("attempt")).intValue()+1;
                String error=String.valueOf(e.getMessage()); error=error.substring(0,Math.min(error.length(),500));
                db.update("""
                    UPDATE ai_course_recommend_job SET status=?,attempt=?,lease_owner=NULL,lease_until=NULL,next_retry_time=?,last_error=?,update_time=NOW(3)
                    WHERE namespace=? AND course_id=? AND revision=? AND lease_owner=?
                    """,attempt>=5?"FAILED":"PENDING",attempt,LocalDateTime.now().plusSeconds(Math.min(300,5L<<Math.min(attempt,6))),error,space.name(),id,rev,owner);
            }
        }
    }
    public Map<String,Object> status() {
        String ns=index.space().name();
        return Map.of("namespace",ns,"mock",index.mock(),"counts",db.queryForList(
                "SELECT status,COUNT(*) AS total FROM ai_course_recommend_job WHERE namespace=? GROUP BY status",ns));
    }
    public int retryFailed() {
        return db.update("UPDATE ai_course_recommend_job SET status='PENDING',attempt=0,next_retry_time=NOW(3),update_time=NOW(3) WHERE namespace=? AND status='FAILED'",index.space().name());
    }
    public int rebuild() {
        return tx.execute(status -> {
            String ns=index.space().name();
            db.queryForList("SELECT namespace FROM ai_course_recommend_sync WHERE namespace=? FOR UPDATE",ns);
            db.update("UPDATE ai_course_recommend_sync SET cursor_id=0,update_time=NOW(3) WHERE namespace=?",ns);
            return db.update("""
                UPDATE ai_course_recommend_job SET revision=revision+1,status='PENDING',attempt=0,lease_owner=NULL,lease_until=NULL,
                next_retry_time=NOW(3),update_time=NOW(3) WHERE namespace=?
                """,ns);
        });
    }
}
