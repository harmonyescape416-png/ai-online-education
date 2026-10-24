package com.kzai.aigc.speech;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15.11-R1 音频格式校验回归：
 * ①浏览器代表性容器魔数全部可识别（Chrome webm/opus、Safari mp4、WAV、MP3、OGG、FLAC）；
 * ②扩展名与容器不一致时以真实格式为准（MediaRecorder Blob type 缺失/映射差异场景）；
 * ③仍拒绝：空文件、魔数无法识别的伪造数据（不通过关闭文件头校验解决问题）。
 */
class AudioValidatorFormatTest {

    private final AudioValidator validator = new AudioValidator();
    private static final long MAX = 5L * 1024 * 1024;

    /** 手工构造合法容器头（仅文件头，测试魔数识别路径） */
    private static byte[] container(String fourcc, int tail) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (fourcc) {
            case "wav" -> {
                out.writeBytes(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'});
                out.write(new byte[tail]);
            }
            case "webm" -> {
                out.writeBytes(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
                out.write(new byte[tail]);
            }
            case "mp4" -> {
                out.writeBytes(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
                out.write(new byte[tail]);
            }
            case "ogg" -> {
                out.writeBytes(new byte[]{'O', 'g', 'g', 'S'});
                out.write(new byte[tail]);
            }
            case "flac" -> {
                out.writeBytes(new byte[]{'f', 'L', 'a', 'C'});
                out.write(new byte[tail]);
            }
            case "mp3" -> {
                out.writeBytes(new byte[]{'I', 'D', '3', 3, 0});
                out.write(new byte[tail]);
            }
            default -> throw new IllegalArgumentException(fourcc);
        }
        return out.toByteArray();
    }

    @Test
    void 浏览器代表容器魔数全部可识别() throws Exception {
        for (String fmt : new String[]{"wav", "webm", "mp4", "ogg", "flac", "mp3"}) {
            AudioValidator.AudioFormat f = validator.validate(
                    container(fmt, 64), "speech." + fmt, MAX);
            assertEquals(fmt, f.code(), "容器 " + fmt + " 应被正确识别");
        }
    }

    @Test
    void 扩展名与容器不一致时以真实格式为准() throws Exception {
        // Chrome MediaRecorder Blob type 缺失导致扩展名映射差异的真实场景：
        // 内容是 webm 但扩展名被映射为 mp4——魔数识别为准，不再 FORGED 拒绝
        AudioValidator.AudioFormat f = validator.validate(
                container("webm", 64), "speech.mp4", MAX);
        assertEquals("webm", f.code(), "真实格式 webm 应胜出");
        AudioValidator.AudioFormat f2 = validator.validate(
                container("mp4", 64), "speech.webm", MAX);
        assertEquals("mp4", f2.code(), "Safari mp4 内容配错扩展名仍以真实格式为准");
    }

    @Test
    void 空文件与伪造数据仍拒绝() {
        var ex1 = assertThrows(SpeechValidationException.class,
                () -> validator.validate(new byte[0], "speech.wav", MAX));
        assertEquals(AudioValidator.ERR_EMPTY, ex1.getErrorCode());
        // 伪造：文本/EXE 数据无法伪装音频文件头
        byte[] fake = "hello this is definitely not an audio file at all........".getBytes();
        var ex2 = assertThrows(SpeechValidationException.class,
                () -> validator.validate(fake, "speech.wav", MAX));
        assertEquals(AudioValidator.ERR_UNKNOWN_FORMAT, ex2.getErrorCode());
        assertTrue(ex2.getMessage().contains("head="), "错误信息携带 hex 诊断头");
    }

    @Test
    void 超限仍拒绝() {
        byte[] big = new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0};
        var ex = assertThrows(SpeechValidationException.class,
                () -> validator.validate(big, "speech.webm", 4));
        assertEquals(AudioValidator.ERR_TOO_LARGE, ex.getErrorCode());
    }
}
