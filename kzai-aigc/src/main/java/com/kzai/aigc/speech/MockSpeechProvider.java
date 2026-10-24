package com.kzai.aigc.speech;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * M14 MOCK 语音 Provider（默认装配；显著标识，绝不冒充真实识别/合成）：
 * <ul>
 *   <li><b>MOCK STT 不是实际识别音频内容</b>——返回由音频字节哈希从固定短语集中
 *       确定性选取的测试文字（同输入同输出，验证上传/校验/回填/确认协议链路）；</li>
 *   <li><b>MOCK TTS 不是实际在线语音合成</b>——按文本长度生成确定时长的合法 WAV
 *       （16kHz 单声道 PCM 正弦波），浏览器可真实播放，验证 Content-Type、分块传输与播放协议；
 *       <b>非流式 TTS</b>（先生成完整 WAV 再分块输出，如实标注）。</li>
 * </ul>
 * 固定短语集覆盖 M8 全部意图类型，便于 E2E 断言「MOCK 不冒充 REAL」。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kzai.aigc", name = "real-enabled", havingValue = "false", matchIfMissing = true)
public class MockSpeechProvider implements SpeechToTextProvider, TextToSpeechProvider {

    public static final String PROVIDER = "MOCK";
    private static final String[] FIXED_PHRASES = {
            "推荐一门学习 Java 微服务的课程",
            "这个课程的退款规则是什么",
            "帮我购买大模型应用开发入门",
            "平台的知识库里有关于证书发放的说明吗",
            "你好",
            "今天天气怎么样"
    };
    public static final String MOCK_NOTE = "MOCK 语音演示，非真实语音识别/合成";

    @Override
    public SttResult transcribe(byte[] audio, String format, int timeoutSeconds) {
        // 确定性：同音频 → 同文字（E2E 可重复断言）；内容与音频语音无关（MOCK 语义红线）
        int idx = Math.floorMod(sha256Hex(audio).hashCode(), FIXED_PHRASES.length);
        String text = FIXED_PHRASES[idx];
        log.info("[speech.mock] STT format={} bytes={} → 固定识别文字#{}（非真实识别）", format, audio.length, idx);
        return new SttResult(text, PROVIDER, "mock-paraformer", true);
    }

    @Override
    public TtsResult synthesize(String text, int timeoutSeconds) {
        // 时长按字符数（中文约 4 字/秒，下限 1s 上限由调用方文本长度限制约束）
        int seconds = Math.max(1, Math.min(text.length() / 4 + 1, 30));
        byte[] wav = renderWav(seconds);
        log.info("[speech.mock] TTS chars={} → 合成 WAV {}ms {} bytes（非真实合成，非流式）",
                text.length(), seconds * 1000, wav.length);
        return new TtsResult(wav, "audio/wav", PROVIDER, "mock-sambert", true);
    }

    @Override
    public boolean mock() {
        return true;
    }

    /** 生成合法 WAV（16kHz/16bit/单声道，440Hz 正弦 + 简单包络，可被浏览器 <audio> 真实播放） */
    static byte[] renderWav(int seconds) {
        int sampleRate = 16000;
        int totalSamples = sampleRate * seconds;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(totalSamples * 2);
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < totalSamples; i++) {
            double t = i / (double) sampleRate;
            double envelope = Math.min(1.0, 6 * t) * Math.min(1.0, 6 * (seconds - t)); // 防爆音包络
            short v = (short) (Math.sin(2 * Math.PI * 440 * t) * 9000 * envelope);
            buf.clear();
            buf.putShort(v);
            pcm.write(buf.array(), 0, 2);
        }
        byte[] data = pcm.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + data.length);
        byte[] header = new byte[44];
        putAscii(header, 0, "RIFF");
        putIntLe(header, 4, 36 + data.length);
        putAscii(header, 8, "WAVE");
        putAscii(header, 12, "fmt ");
        putIntLe(header, 16, 16);
        putShortLe(header, 20, (short) 1);      // PCM
        putShortLe(header, 22, (short) 1);      // mono
        putIntLe(header, 24, sampleRate);
        putIntLe(header, 28, sampleRate * 2);   // byte rate
        putShortLe(header, 32, (short) 2);      // block align
        putShortLe(header, 34, (short) 16);     // bits
        putAscii(header, 36, "data");
        putIntLe(header, 40, data.length);
        try {
            out.write(header);
            out.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("WAV 生成失败", e);
        }
        return out.toByteArray();
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putAscii(byte[] b, int off, String s) {
        byte[] a = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(a, 0, b, off, a.length);
    }

    private static void putIntLe(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    private static void putShortLe(byte[] b, int off, short v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
    }
}
