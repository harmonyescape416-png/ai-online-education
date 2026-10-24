package com.kzai.aigc.speech;

/**
 * M14 文字转语音 Provider 抽象。
 * <p>
 * 流式语义（任务书 6.2）：{@code streaming()} 必须如实返回 Provider 真实能力——
 * sambert 为非流式 HTTP（整段生成后返回），MOCK 同为非流式（生成完整 WAV 后由
 * 控制器分块写出，仅验证分块传输协议）；两者页面/接口均显著标注「非流式 TTS」。
 * CosyVoice（WebSocket 增量合成）为真流式候选，留待真实凭据验收后评估接入。
 */
public interface TextToSpeechProvider {

    /**
     * 文字转语音。
     *
     * @param text           纯文本（已去 Markdown，长度已按动态参数限制）
     * @param timeoutSeconds 请求超时秒
     * @return 合成结果（audio 为完整音频字节；contentType 如 audio/wav）
     */
    TtsResult synthesize(String text, int timeoutSeconds);

    boolean mock();

    /** 是否真流式（当前实现均为 false，接口/页面显著标注「非流式 TTS」） */
    default boolean streaming() {
        return false;
    }

    record TtsResult(byte[] audio, String contentType, String provider, String modelCode, boolean mock) {}
}
