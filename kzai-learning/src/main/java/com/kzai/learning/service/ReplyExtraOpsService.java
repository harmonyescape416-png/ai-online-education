package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.ResultCode;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.InteractionReplyExtra;
import com.kzai.learning.entity.InteractionReplyHelpful;
import com.kzai.learning.mapper.InteractionReplyExtraMapper;
import com.kzai.learning.mapper.InteractionReplyHelpfulMapper;
import com.kzai.learning.mapper.InteractionReplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M15.21：回复扩展操作（AI 检查标记/采纳标记）——校验链+唯一记录。
 * <ul>
 *   <li>教师检查前校验：回复存在、属于本人课程、确为 AI 回复（SYSTEM_AI 常量）；</li>
 *   <li>采纳按 userId+replyId 唯一记录（interaction_reply_helpful，幂等标记/撤销，计数=COUNT(*)，
 *       不再对总数无条件加减）；不能为不存在的回复创建扩展行；不能撤销他人记录。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplyExtraOpsService {

    private final InteractionReplyExtraMapper extraMapper;
    private final InteractionReplyMapper replyMapper;
    private final InteractionReplyHelpfulMapper helpfulMapper;
    private final QuestionService questionService;

    /** 教师：标记 AI 回复已检查（校验回复存在/AI 来源；课程归属由 Controller requireOwned 保证） */
    @Transactional
    public void markAiChecked(Long replyId, Long teacherId) {
        InteractionReply reply = requireReply(replyId);
        InteractionReplyExtra extra = requireExtra(replyId);
        if (!InteractionReplyExtra.SOURCE_SYSTEM_AI.equals(extra.getSource())) {
            throw new BizException("仅 AI 回复需要检查标记");
        }
        if (extra.getAiChecked() != null && extra.getAiChecked() == 1) {
            return; // 幂等
        }
        extra.setAiChecked(1);
        extraMapper.updateById(extra);
        log.info("[ai-check] 回复 {} 已由教师 {} 标记检查", replyId, teacherId);
    }

    /**
     * 学员：标记「有帮助」（唯一记录；重复点击幂等；回复必须存在；学员可见性由调用方保证）。
     *
     * @return 标记后的采纳总数（COUNT(*)）
     */
    @Transactional
    public long markHelpful(Long replyId, Long userId) {
        questionService.assertReplyFeedbackAllowed(userId, replyId);
        InteractionReplyExtra extra = requireExtra(replyId);
        if (!InteractionReplyExtra.SOURCE_SYSTEM_AI.equals(extra.getSource())) {
            throw new BizException("只能对 AI 回复标记有帮助");
        }
        if (extra.getCorrected() != null && extra.getCorrected() == 1) {
            throw new BizException("该回复已经人工修正，不能再标记有帮助");
        }
        requireReply(replyId);
        Long exists = helpfulMapper.selectCount(Wrappers.<InteractionReplyHelpful>lambdaQuery()
                .eq(InteractionReplyHelpful::getReplyId, replyId)
                .eq(InteractionReplyHelpful::getUserId, userId));
        if (exists == null || exists == 0) {
            InteractionReplyHelpful h = new InteractionReplyHelpful();
            h.setReplyId(replyId);
            h.setUserId(userId);
            try {
                helpfulMapper.insert(h);
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 并发重复请求：按成功处理（唯一键幂等）
            }
        }
        return countHelpful(replyId);
    }

    /**
     * 学员：撤销自己的「有帮助」（仅能撤销本人记录）。
     *
     * @return 撤销后的采纳总数
     */
    @Transactional
    public long unmarkHelpful(Long replyId, Long userId) {
        questionService.assertReplyFeedbackAllowed(userId, replyId);
        helpfulMapper.delete(Wrappers.<InteractionReplyHelpful>lambdaQuery()
                .eq(InteractionReplyHelpful::getReplyId, replyId)
                .eq(InteractionReplyHelpful::getUserId, userId)); // 仅删本人记录
        return countHelpful(replyId);
    }

    /** 采纳总数（COUNT(*)——去重口径） */
    public long countHelpful(Long replyId) {
        return helpfulMapper.selectCount(Wrappers.<InteractionReplyHelpful>lambdaQuery()
                .eq(InteractionReplyHelpful::getReplyId, replyId));
    }

    /** 学员是否已标记（页面刷新状态保持） */
    public boolean isMarkedBy(Long replyId, Long userId) {
        questionService.assertReplyFeedbackAllowed(userId, replyId);
        return helpfulMapper.selectCount(Wrappers.<InteractionReplyHelpful>lambdaQuery()
                .eq(InteractionReplyHelpful::getReplyId, replyId)
                .eq(InteractionReplyHelpful::getUserId, userId)) > 0;
    }

    private InteractionReply requireReply(Long replyId) {
        InteractionReply reply = replyMapper.selectById(replyId);
        if (reply == null || (reply.getHidden() != null && reply.getHidden() == 1)) {
            throw new BizException("回复不存在或已隐藏");
        }
        return reply;
    }

    private InteractionReplyExtra requireExtra(Long replyId) {
        InteractionReplyExtra extra = extraMapper.selectOne(Wrappers
                .<InteractionReplyExtra>lambdaQuery()
                .eq(InteractionReplyExtra::getReplyId, replyId).last("LIMIT 1"));
        if (extra == null) {
            // 不为不存在的扩展行继续操作（扩展行缺失=人工回复或数据异常）
            throw new BizException("回复扩展信息不存在，无法标记");
        }
        return extra;
    }
}
