package com.kzai.aigc.prompt;

import com.kzai.aigc.agent.AgentTypeEnum;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单个 Agent 的生效 Prompt 快照（M8）。
 * <p>
 * 生成开始时由 ChatService 读取一次并在整轮生成内保持稳定
 * （运行中的生成不因发布/回滚切换模板）；字段不含任何密钥或敏感配置。
 */
@Data
@Builder
public class AgentPromptSpec {

    private AgentTypeEnum agentType;
    /** 系统提示词（模板变量仅限白名单占位符，见 PromptManager.validate） */
    private String systemPrompt;
    /** 欢迎语（前端展示） */
    private String welcomeText;
    /** 推荐问题（前端展示） */
    private List<String> hotQuestions;
    /** 来源：BUILTIN（代码默认）/ NACOS（已发布生效） */
    private String source;
    /** 版本标识：内置为 builtin；发布为版本内容摘要（见 PromptManager.contentVersion） */
    private String version;
}
