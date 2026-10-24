package com.kzai.aigc.speech;

/**
 * M14 语音 Provider 抽象（独立于 ChatProvider——语音 SDK/HTTP API 与文本模型接口形态不同，
 * 不硬塞进 ChatProvider；与 M6 ChatProvider 的 MOCK/REAL 装配模式保持一致）。
 * <p>
 * 真实实现基于 DashScope 官方 HTTP API（选型依据见执行报告 §5）：
 * <ul>
 *   <li>STT：Paraformer 录音文件识别（同步 HTTP，输入 wav/mp3/opus/aac/m4a 等）；</li>
 *   <li>TTS：sambert 语音合成（HTTP 非流式，输出 wav/mp3）——CosyVoice 支持流式但需 WebSocket，
 *       当前版本未接入，真实流式能力标记未验证；</li>
 * </ul>
 * 无凭据（DASHSCOPE_ENABLED != true 或 Key 缺失）时装配 {@link MockSpeechProvider}。
 */
public interface SpeechToTextProvider {

    /**
     * 语音转文字。
     *
     * @param audio        音频字节（已过魔数/大小校验）
     * @param format       真实音频格式（wav/mp3/ogg/webm/m4a/mp4/flac/aac）
     * @param timeoutSeconds 请求超时秒（来自动态参数）
     * @return 识别结果（mock 标志必须如实）
     */
    SttResult transcribe(byte[] audio, String format, int timeoutSeconds);

    boolean mock();

    record SttResult(String text, String provider, String modelCode, boolean mock) {}
}
