package com.kzai.aigc.speech;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * M14 音频真实格式校验（魔数文件头，不信任扩展名/Content-Type）。
 * <ul>
 *   <li>支持格式白名单：WAV/MP3/OGG/WEBM/M4A-MP4/FLAC/AAC(ADTS)；</li>
 *   <li>扩展名伪造（如 .wav 实为文本/EXE）一律拒绝；</li>
 *   <li>大小上限由调用方按动态参数传入。</li>
 * </ul>
 */
@Component
public class AudioValidator {

    public record AudioFormat(String code, String description) {}

    private static final Set<String> ALLOWED = Set.of("wav", "mp3", "ogg", "webm", "m4a", "mp4", "flac", "aac");

    /** 异常类型（受控错误码，对应 E2E 断言） */
    public static final String ERR_EMPTY = "EMPTY_FILE";
    public static final String ERR_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String ERR_UNKNOWN_FORMAT = "UNSUPPORTED_AUDIO";
    public static final String ERR_FORGED_EXT = "FORGED_EXTENSION";

    /**
     * 校验并识别真实音频格式。
     * <p>M15.11-R1 修复：浏览器 MediaRecorder/WebAudio 的容器与扩展名可能不一致
     * （Blob type 缺失、浏览器默认容器差异等）——<b>魔数可识别的合法音频一律以真实格式为准</b>
     * （防伪造由魔数校验承担：文本/EXE/任意数据无法伪装出合法音频文件头）；
     * 仍拒绝：空文件、超限、魔数无法识别的任意数据。
     *
     * @throws SpeechValidationException 携带受控错误码
     */
    public AudioFormat validate(byte[] data, String filename, long maxBytes) {
        if (data == null || data.length == 0) {
            throw new SpeechValidationException(ERR_EMPTY, "上传文件为空");
        }
        if (data.length > maxBytes) {
            throw new SpeechValidationException(ERR_TOO_LARGE,
                    "文件超过大小上限（" + (maxBytes / 1024 / 1024) + "MB，实际 " + (data.length / 1024) + "KB）");
        }
        String magic = detectMagic(data);
        if (magic == null) {
            throw new SpeechValidationException(ERR_UNKNOWN_FORMAT,
                    "无法识别的文件头（非已知音频格式）：" + hexHead(data));
        }
        return new AudioFormat(magic, magic);
    }

    /** 诊断辅助：前 16 字节 hex（用于定位未知文件头；不含业务内容） */
    private static String hexHead(byte[] d) {
        StringBuilder sb = new StringBuilder("head=");
        int n = Math.min(16, d.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", d[i] & 0xFF));
        }
        return sb.toString();
    }

    /** 真实格式魔数识别；未识别返回 null */
    public String detectMagic(byte[] d) {
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E') {
            return "wav";
        }
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            return "mp3";
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) {
            return "mp3"; // MPEG 帧同步（裸 MP3/ADTS AAC 皆有 0xFFFx 前缀，交由 Provider 进一步处理）
        }
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') {
            return "ogg";
        }
        if (d.length >= 4 && (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3) {
            return "webm"; // Matroska/EBML（Chrome MediaRecorder 默认输出 webm/opus）
            // M15.11-R1 修复：原 d[2]==0xDF 有符号 byte 永远 false（0xDF=-33）——
            // Chrome webm 录音的 EBML 头无法命中，即「无法识别的文件头」故障根因
        }
        if (d.length >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            return "mp4"; // ftyp box（MP4/M4A 同源）
        }
        if (d.length >= 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C') {
            return "flac";
        }
        return null;
    }

    private static String extOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
