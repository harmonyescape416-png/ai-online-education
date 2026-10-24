package com.kzai.learning.note;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.client.UserClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.dto.LearnChapterDTO;
import com.kzai.learning.points.PointClock;
import com.kzai.learning.points.PointService;
import com.kzai.learning.service.EntitlementService;
import com.kzai.learning.util.OperationAuditLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 学习笔记。和课程问答分开存储。
 * 默认私有。公开笔记只有当前能学习该章节的人能看正文和评论。
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    static final int TITLE_MAX = 80;
    static final int CONTENT_MAX = 4000;
    static final int COMMENT_MAX = 500;
    private static final int PAGE_MAX = 20;

    private final LearningNoteMapper noteMapper;
    private final LearningNoteLikeMapper likeMapper;
    private final LearningNoteCommentMapper commentMapper;
    private final CourseClient courseClient;
    private final EntitlementService entitlementService;
    private final UserClient userClient;
    private final PointService pointService;
    private final PointClock clock;
    private final OperationAuditLogger auditLogger;

    @Transactional
    public Map<String, Object> create(long userId, Long courseId, Long chapterId, String title, String content,
                                      Integer position, String visibility) {
        LearnChapterDTO chapter = requireChapter(courseId, chapterId);
        CourseOutlineDTO outline = outline(courseId);
        boolean publish = LearningNote.PUBLIC.equals(normalizeVisibility(visibility));
        if (!canStudy(userId, outline, chapter)) {
            throw new BizException("没有该章节的学习权限，不能记笔记");
        }
        LocalDateTime now = clock.now();
        LearningNote note = new LearningNote();
        note.setUserId(userId);
        note.setCourseId(courseId);
        note.setChapterId(chapterId);
        note.setTitle(text(title, TITLE_MAX, "标题"));
        note.setContent(text(content, CONTENT_MAX, "正文"));
        note.setVideoPositionSec(position(position, chapter));
        note.setVisibility(publish ? LearningNote.PUBLIC : LearningNote.PRIVATE);
        note.setDeleted(0);
        note.setLikeCount(0);
        note.setCommentCount(0);
        note.setCreateTime(now);
        note.setUpdateTime(now);
        noteMapper.insert(note);
        return full(note, userId, true);
    }

    @Transactional
    public Map<String, Object> update(long userId, long noteId, Long chapterId, String title, String content,
                                      Integer position, boolean touchPosition, String visibility) {
        LearningNote note = own(userId, noteId);
        LearnChapterDTO chapter = requireChapter(note.getCourseId(), chapterId == null ? note.getChapterId() : chapterId);
        if (!chapter.getCourseId().equals(note.getCourseId())) {
            throw new BizException("章节不属于该课程");
        }
        String nextVisibility = visibility == null ? note.getVisibility() : normalizeVisibility(visibility);
        boolean publishing = LearningNote.PUBLIC.equals(nextVisibility)
                && !LearningNote.PUBLIC.equals(note.getVisibility());
        boolean moving = !chapter.getId().equals(note.getChapterId());
        if (publishing || moving || LearningNote.PUBLIC.equals(nextVisibility)) {
            if (!canStudy(userId, outline(note.getCourseId()), chapter)) {
                throw new BizException("没有该章节的学习权限，不能公开或改到该章节");
            }
        }
        note.setChapterId(chapter.getId());
        if (title != null) {
            note.setTitle(text(title, TITLE_MAX, "标题"));
        }
        if (content != null) {
            note.setContent(text(content, CONTENT_MAX, "正文"));
        }
        if (touchPosition) {
            note.setVideoPositionSec(position(position, chapter));
        }
        note.setVisibility(nextVisibility);
        note.setUpdateTime(clock.now());
        noteMapper.updateById(note);
        return full(note, userId, true);
    }

    @Transactional
    public void remove(long userId, long noteId) {
        LearningNote note = own(userId, noteId);
        noteMapper.update(null, Wrappers.<LearningNote>lambdaUpdate()
                .set(LearningNote::getDeleted, 1)
                .set(LearningNote::getUpdateTime, clock.now())
                .eq(LearningNote::getId, note.getId())
                .eq(LearningNote::getDeleted, 0));
    }

    public Map<String, Object> mine(long userId, Long courseId, String keyword, long page, long size) {
        String word = keyword == null ? "" : keyword.trim();
        if (word.length() > 40) {
            throw new BizException("搜索词不能超过40字");
        }
        Page<LearningNote> result = noteMapper.selectPage(pageOf(page, size),
                Wrappers.<LearningNote>lambdaQuery()
                        .eq(LearningNote::getUserId, userId)
                        .eq(courseId != null, LearningNote::getCourseId, courseId)
                        .eq(LearningNote::getDeleted, 0)
                        .and(!word.isEmpty(), query -> query.like(LearningNote::getTitle, word)
                                .or().like(LearningNote::getContent, word))
                        .orderByDesc(LearningNote::getUpdateTime)
                        .orderByDesc(LearningNote::getId));
        return pageView(result, userId, true);
    }

    public Map<String, Object> publicList(long userId, Long courseId, Long chapterId, String sort, long page, long size) {
        if (courseId == null) {
            throw new BizException("请选择课程");
        }
        CourseOutlineDTO outline = outline(courseId);
        Page<LearningNote> result = noteMapper.selectPage(pageOf(page, size),
                Wrappers.<LearningNote>lambdaQuery()
                        .eq(LearningNote::getCourseId, courseId)
                        .eq(chapterId != null, LearningNote::getChapterId, chapterId)
                        .eq(LearningNote::getVisibility, LearningNote.PUBLIC)
                        .eq(LearningNote::getDeleted, 0)
                        .orderByDesc("hot".equals(sort), LearningNote::getLikeCount)
                        .orderByDesc(!"hot".equals(sort), LearningNote::getUpdateTime)
                        .orderByDesc(LearningNote::getId));
        Map<Long, LearnChapterDTO> chapters = chapters(outline);
        List<Map<String, Object>> records = new ArrayList<>();
        Map<Long, String> names = names(result.getRecords().stream().map(LearningNote::getUserId).toList());
        for (LearningNote note : result.getRecords()) {
            LearnChapterDTO chapter = chapters.get(note.getChapterId());
            boolean open = chapter != null && canStudy(userId, outline, chapter);
            records.add(open || note.getUserId().equals(userId) ? full(note, userId, names) : locked(note));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", result.getTotal());
        view.put("pageNum", result.getCurrent());
        view.put("pageSize", result.getSize());
        view.put("sort", "hot".equals(sort) ? "hot" : "time");
        view.put("records", records);
        return view;
    }

    public Map<String, Object> detail(long userId, long noteId) {
        LearningNote note = live(noteId);
        if (note.getUserId().equals(userId)) {
            return full(note, userId, true);
        }
        if (!LearningNote.PUBLIC.equals(note.getVisibility())) {
            throw new BizException("笔记不存在");
        }
        LearnChapterDTO chapter = findChapter(outline(note.getCourseId()), note.getChapterId());
        if (chapter == null || !canStudy(userId, outline(note.getCourseId()), chapter)) {
            return locked(note);
        }
        return full(note, userId, true);
    }

    @Transactional
    public Map<String, Object> like(long userId, long noteId) {
        LearningNote note = readable(userId, noteId);
        LearningNoteLike row = new LearningNoteLike();
        row.setNoteId(noteId);
        row.setUserId(userId);
        row.setCreateTime(clock.now());
        try {
            likeMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            return Map.of("liked", true, "likeCount", countOf(note.getLikeCount()), "repeated", true);
        }
        noteMapper.update(null, Wrappers.<LearningNote>lambdaUpdate()
                .setSql("like_count = like_count + 1")
                .eq(LearningNote::getId, noteId)
                .eq(LearningNote::getDeleted, 0));
        if (!note.getUserId().equals(userId)) {
            pointService.award(note.getUserId(), "NOTE_LIKE", "NOTE_LIKE:" + noteId + ":" + userId, clock.today());
        }
        LearningNote saved = noteMapper.selectById(noteId);
        return Map.of("liked", true, "likeCount", countOf(saved.getLikeCount()), "repeated", false);
    }

    @Transactional
    public Map<String, Object> unlike(long userId, long noteId) {
        readable(userId, noteId);
        int removed = likeMapper.delete(Wrappers.<LearningNoteLike>lambdaQuery()
                .eq(LearningNoteLike::getNoteId, noteId)
                .eq(LearningNoteLike::getUserId, userId));
        if (removed == 1) {
            noteMapper.update(null, Wrappers.<LearningNote>lambdaUpdate()
                    .setSql("like_count = CASE WHEN like_count > 0 THEN like_count - 1 ELSE 0 END")
                    .eq(LearningNote::getId, noteId));
        }
        LearningNote saved = noteMapper.selectById(noteId);
        return Map.of("liked", false, "likeCount", saved == null ? 0 : countOf(saved.getLikeCount()));
    }

    public Map<String, Object> comments(long userId, long noteId, long page, long size) {
        readable(userId, noteId);
        Page<LearningNoteComment> result = commentMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), PAGE_MAX)),
                Wrappers.<LearningNoteComment>lambdaQuery()
                        .eq(LearningNoteComment::getNoteId, noteId)
                        .eq(LearningNoteComment::getDeleted, 0)
                        .orderByAsc(LearningNoteComment::getId));
        Map<Long, String> names = names(result.getRecords().stream().map(LearningNoteComment::getUserId).toList());
        LearningNote note = live(noteId);
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(row -> commentView(row, names, userId, note.getUserId())).toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", result.getTotal());
        view.put("records", records);
        return view;
    }

    @Transactional
    public Map<String, Object> comment(long userId, long noteId, String content) {
        LearningNote note = readable(userId, noteId);
        LearningNoteComment row = new LearningNoteComment();
        row.setNoteId(noteId);
        row.setUserId(userId);
        row.setContent(text(content, COMMENT_MAX, "评论"));
        row.setDeleted(0);
        row.setCreateTime(clock.now());
        commentMapper.insert(row);
        noteMapper.update(null, Wrappers.<LearningNote>lambdaUpdate()
                .setSql("comment_count = comment_count + 1")
                .eq(LearningNote::getId, noteId)
                .eq(LearningNote::getDeleted, 0));
        if (!note.getUserId().equals(userId)) {
            pointService.award(note.getUserId(), "NOTE_COMMENT", "NOTE_COMMENT:" + row.getId(), clock.today());
        }
        return commentView(row, names(List.of(userId)), userId, note.getUserId());
    }

    @Transactional
    public void removeComment(long userId, long noteId, long commentId) {
        LearningNote note = readable(userId, noteId);
        LearningNoteComment comment = commentMapper.selectById(commentId);
        if (comment == null || !Long.valueOf(noteId).equals(comment.getNoteId()) || Integer.valueOf(1).equals(comment.getDeleted())) {
            throw new BizException("评论不存在");
        }
        if (!comment.getUserId().equals(userId) && !note.getUserId().equals(userId)) {
            throw new BizException("只能删除自己的评论");
        }
        int changed = commentMapper.update(null, Wrappers.<LearningNoteComment>lambdaUpdate()
                .set(LearningNoteComment::getDeleted, 1)
                .eq(LearningNoteComment::getId, commentId)
                .eq(LearningNoteComment::getDeleted, 0));
        if (changed == 1) {
            noteMapper.update(null, Wrappers.<LearningNote>lambdaUpdate()
                    .setSql("comment_count = CASE WHEN comment_count > 0 THEN comment_count - 1 ELSE 0 END")
                    .eq(LearningNote::getId, noteId));
        }
    }

    @Transactional
    public void adminDelete(long operatorId, long noteId, String reason) {
        String why = text(reason, 200, "原因");
        LearningNote note = live(noteId);
        noteMapper.update(null, Wrappers.<LearningNote>lambdaUpdate()
                .set(LearningNote::getDeleted, 1)
                .set(LearningNote::getUpdateTime, clock.now())
                .eq(LearningNote::getId, note.getId())
                .eq(LearningNote::getDeleted, 0));
        auditLogger.log(operatorId, "", "note", "DELETE", "learning_note", noteId, why);
    }

    @Transactional
    public Map<String, Object> adminHide(long operatorId, long noteId, String reason) {
        String why = text(reason, 200, "原因");
        LearningNote note = live(noteId);
        noteMapper.update(null, Wrappers.<LearningNote>lambdaUpdate()
                .set(LearningNote::getVisibility, LearningNote.PRIVATE)
                .set(LearningNote::getUpdateTime, clock.now())
                .eq(LearningNote::getId, note.getId()));
        auditLogger.log(operatorId, "", "note", "HIDE", "learning_note", noteId, why);
        LearningNote saved = noteMapper.selectById(noteId);
        return full(saved, operatorId, true);
    }

    private LearningNote readable(long userId, long noteId) {
        LearningNote note = live(noteId);
        if (note.getUserId().equals(userId)) {
            return note;
        }
        if (!LearningNote.PUBLIC.equals(note.getVisibility())) {
            throw new BizException("笔记不存在");
        }
        CourseOutlineDTO outline = outline(note.getCourseId());
        LearnChapterDTO chapter = findChapter(outline, note.getChapterId());
        if (chapter == null || !canStudy(userId, outline, chapter)) {
            throw new BizException("没有该章节的学习权限，不能查看笔记");
        }
        return note;
    }

    private LearningNote own(long userId, long noteId) {
        LearningNote note = live(noteId);
        if (!note.getUserId().equals(userId)) {
            throw new BizException("只能编辑自己的笔记");
        }
        return note;
    }

    private LearningNote live(long noteId) {
        LearningNote note = noteMapper.selectById(noteId);
        if (note == null || Integer.valueOf(1).equals(note.getDeleted())) {
            throw new BizException("笔记不存在");
        }
        return note;
    }

    private CourseOutlineDTO outline(Long courseId) {
        R<CourseOutlineDTO> result;
        try {
            result = courseClient.learnOutline(courseId);
        } catch (RuntimeException ex) {
            throw new BizException("课程信息暂时无法校验");
        }
        CourseOutlineDTO outline = result == null ? null : result.getData();
        if (outline == null || outline.getId() == null) {
            throw new BizException("课程不存在");
        }
        return outline;
    }

    private LearnChapterDTO requireChapter(Long courseId, Long chapterId) {
        if (courseId == null || chapterId == null) {
            throw new BizException("请选择课程章节");
        }
        LearnChapterDTO chapter = findChapter(outline(courseId), chapterId);
        if (chapter == null || !courseId.equals(chapter.getCourseId())) {
            throw new BizException("章节不属于该课程");
        }
        return chapter;
    }

    private LearnChapterDTO findChapter(CourseOutlineDTO outline, Long chapterId) {
        if (outline.getChapters() == null || chapterId == null) {
            return null;
        }
        return outline.getChapters().stream()
                .filter(chapter -> chapterId.equals(chapter.getId()))
                .findFirst().orElse(null);
    }

    private Map<Long, LearnChapterDTO> chapters(CourseOutlineDTO outline) {
        Map<Long, LearnChapterDTO> map = new LinkedHashMap<>();
        if (outline.getChapters() != null) {
            for (LearnChapterDTO chapter : outline.getChapters()) {
                map.put(chapter.getId(), chapter);
            }
        }
        return map;
    }

    private boolean canStudy(long userId, CourseOutlineDTO outline, LearnChapterDTO chapter) {
        if (outline == null || !Boolean.TRUE.equals(outline.getStudentVisible()) || chapter == null) {
            return false;
        }
        if (Boolean.TRUE.equals(chapter.getFreePreview())) {
            return true;
        }
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        return decision != null && Boolean.TRUE.equals(decision.getAllowed());
    }

    private Integer position(Integer seconds, LearnChapterDTO chapter) {
        if (seconds == null) {
            return null;
        }
        if (seconds < 0) {
            throw new BizException("播放时间点不能为负");
        }
        int cap = chapter.getVideoDurationSec() != null && chapter.getVideoDurationSec() > 0
                ? chapter.getVideoDurationSec() : 86400;
        if (seconds > cap) {
            throw new BizException("播放时间点超出章节范围");
        }
        return seconds;
    }

    private String normalizeVisibility(String value) {
        if (value == null || value.isBlank() || LearningNote.PRIVATE.equals(value)) {
            return LearningNote.PRIVATE;
        }
        if (LearningNote.PUBLIC.equals(value)) {
            return LearningNote.PUBLIC;
        }
        throw new BizException("可见性只能是私有或公开");
    }

    private String text(String value, int max, String label) {
        if (value == null) {
            throw new BizException("请填写" + label);
        }
        String cleaned = value.replace("\u0000", "").trim();
        if (cleaned.isEmpty()) {
            throw new BizException("请填写" + label);
        }
        if (cleaned.length() > max) {
            throw new BizException(label + "不能超过" + max + "字");
        }
        return cleaned;
    }

    private Page<LearningNote> pageOf(long page, long size) {
        return new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), PAGE_MAX));
    }

    private Map<String, Object> pageView(Page<LearningNote> result, long viewerId, boolean owner) {
        Map<Long, String> names = names(result.getRecords().stream().map(LearningNote::getUserId).toList());
        List<Map<String, Object>> records = new ArrayList<>();
        for (LearningNote note : result.getRecords()) {
            records.add(owner ? full(note, viewerId, names) : locked(note));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", result.getTotal());
        view.put("pageNum", result.getCurrent());
        view.put("pageSize", result.getSize());
        view.put("records", records);
        return view;
    }

    private Map<String, Object> full(LearningNote note, long viewerId, boolean loadName) {
        return full(note, viewerId, loadName ? names(List.of(note.getUserId())) : Map.of());
    }

    private Map<String, Object> full(LearningNote note, long viewerId, Map<Long, String> names) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(note.getId()));
        view.put("courseId", String.valueOf(note.getCourseId()));
        view.put("chapterId", String.valueOf(note.getChapterId()));
        view.put("userId", String.valueOf(note.getUserId()));
        view.put("authorName", publicName(names.get(note.getUserId()), note.getUserId()));
        view.put("mine", note.getUserId().equals(viewerId));
        view.put("title", note.getTitle());
        view.put("content", note.getContent());
        view.put("videoPositionSec", note.getVideoPositionSec());
        view.put("visibility", note.getVisibility());
        view.put("likeCount", countOf(note.getLikeCount()));
        view.put("commentCount", countOf(note.getCommentCount()));
        view.put("liked", liked(viewerId, note.getId()));
        view.put("locked", false);
        view.put("createTime", note.getCreateTime());
        view.put("updateTime", note.getUpdateTime());
        return view;
    }

    private Map<String, Object> locked(LearningNote note) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(note.getId()));
        view.put("courseId", String.valueOf(note.getCourseId()));
        view.put("chapterId", String.valueOf(note.getChapterId()));
        view.put("locked", true);
        view.put("title", "");
        view.put("content", null);
        view.put("videoPositionSec", null);
        view.put("visibility", LearningNote.PUBLIC);
        view.put("reason", "未购、已退款或权益到期后不能查看该章节的公开笔记");
        view.put("createTime", note.getCreateTime());
        return view;
    }

    private Map<String, Object> commentView(LearningNoteComment row, Map<Long, String> names, long viewerId, long ownerId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(row.getId()));
        view.put("noteId", String.valueOf(row.getNoteId()));
        view.put("userId", String.valueOf(row.getUserId()));
        view.put("authorName", publicName(names.get(row.getUserId()), row.getUserId()));
        view.put("content", row.getContent());
        view.put("canDelete", row.getUserId().equals(viewerId) || ownerId == viewerId);
        view.put("createTime", row.getCreateTime());
        return view;
    }

    private boolean liked(long userId, long noteId) {
        return likeMapper.selectCount(Wrappers.<LearningNoteLike>lambdaQuery()
                .eq(LearningNoteLike::getNoteId, noteId)
                .eq(LearningNoteLike::getUserId, userId)) > 0;
    }

    private int countOf(Integer value) {
        return value == null ? 0 : value;
    }

    private Map<Long, String> names(List<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            R<Map<Long, String>> result = userClient.names(ids);
            return result == null || result.getData() == null ? Map.of() : result.getData();
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private String publicName(String name, long userId) {
        if (name != null && name.matches("1\\d{10}")) {
            return name.substring(0, 3) + "****" + name.substring(7);
        }
        if (name == null || name.isBlank()) {
            String id = String.valueOf(userId);
            return "学员" + id.substring(Math.max(0, id.length() - 4));
        }
        return name;
    }
}
