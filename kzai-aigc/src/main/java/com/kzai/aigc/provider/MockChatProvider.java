package com.kzai.aigc.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * MOCK Provider：仅在未配置 DASHSCOPE_API_KEY 或显式设置 kzai.aigc.force-mock=true 时生效。
 * <p>
 * 目的：验证 SSE 流式管线、协议解析、Redis 记忆、停止生成、跨用户隔离等系统流程，
 * 而<b>不</b>验证真实模型质量。<b>所有响应不得冒充真实模型</b>——UI、接口响应（mock=true）、
 * 日志与报告均显著标识 MOCK。Mock 单元/集成测试与真实 AI 模型测试在报告中分别统计。
 * <p>
 * 行为：按句号/换行切片，模拟增量返回；故意延迟以触发与真实模型相同的 streaming/timing 测量。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "dashScopeChatClient")
public class MockChatProvider implements ChatProvider {

    @Value("${kzai.aigc.mock.delay-ms:300}")
    private int delayMs;

    @Override
    public String code() {
        return "MOCK";
    }

    @Override
    public boolean mock() {
        return true;
    }

    @Override
    public String call(List<Message> messages) {
        return "【MOCK】" + mockReply(messages);
    }

    @Override
    public Flux<String> stream(List<Message> messages) {
        String reply = mockReply(messages);
        String[] parts = reply.split("(?<=[。！？!?\\n])");
        return Flux.fromArray(parts)
                .filter(s -> !s.isEmpty())
                .delayElements(Duration.ofMillis(Math.max(delayMs, 1)))
                .map(s -> s);
    }

    private String mockReply(List<Message> messages) {
        String lastUser = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if ("USER".equalsIgnoreCase(m.getMessageType().name())) {
                lastUser = m.getText();
                break;
            }
        }
        String topic = (lastUser == null || lastUser.isBlank()) ? "通用教育" : lastUser;
        if (topic.length() > 30) {
            topic = topic.substring(0, 30) + "…";
        }
        return "您好，我是「课栈智伴」AI 工作台 MOCK 模式（M6 验证专用，**非真实模型**）。\n"
                + "已收到您的问题：「" + topic + "」。\n"
                + "MOCK 模式仅用于验证流式管线与协议，不会调用真实大模型。\n"
                + "如需真实 AI 体验，请配置 `DASHSCOPE_API_KEY` 并取消 `KZAI_AIGC_FORCE_MOCK` 后重启 aigc。";
    }
}
