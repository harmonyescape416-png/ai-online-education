package com.kzai.aigc.speech;

import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M14 语音接口（原始设计 5.2：POST /audio/stt、POST /audio/tts-stream；网关 /audio/** → aigc）。
 * <p>
 * 安全红线：
 * <ul>
 *   <li>音频<b>不落库</b>：仅内存处理（multipart 字节数组），请求结束即释放，无任何持久化；</li>
 *   <li>身份取自网关验证后的 UserContext，不接受请求参数身份；</li>
 *   <li>日志只记录 requestId/大小/格式/耗时/Provider/成败，<b>不记录音频内容与识别正文</b>；</li>
 *   <li>TTS 分块写出（chunked），客户端断开由容器感知终止写入；<b>非流式合成如实标注</b>
 *       （streaming=false 时响应头 X-Tts-Streaming:false）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SpeechController {

    private final SpeechService speechService;

    /** STT：multipart 上传 → 校验 → Provider → {text, provider, modelCode, mock, requestId} */
    @PostMapping("/audio/stt")
    public R<Map<String, Object>> stt(@RequestParam(value = "file", required = false) MultipartFile file,
                                      HttpServletRequest request) {
        long userId = UserContext.getUserId();
        String requestId = UserContext.getRequestId();
        if (file == null || file.isEmpty()) {
            throw new com.kzai.common.exception.BizException("上传文件不能为空");
        }
        byte[] audio;
        try {
            audio = file.getBytes();
        } catch (IOException ex) {
            throw new com.kzai.common.exception.BizException("读取上传文件失败");
        }
        SpeechService.SttOutcome out = speechService.transcribe(userId, audio,
                file.getOriginalFilename(), requestId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", out.text());
        data.put("provider", out.provider());
        data.put("modelCode", out.modelCode());
        data.put("mock", out.mock());
        data.put("requestId", requestId);
        return R.ok(data);
    }

    /**
     * TTS：文字 → 音频二进制流（chunked）。成功时直接写音频字节（非 R 包装）。
     * 失败以 JSON（R 结构，HTTP 200 + code≠200）返回，前端按响应类型区分处理。
     */
    @PostMapping(value = "/audio/tts-stream")
    public void tts(@RequestParam(value = "text", required = false) String text,
                    HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
        long userId = UserContext.getUserId();
        String requestId = UserContext.getRequestId();
        try {
            SpeechService.TtsOutcome out = speechService.synthesize(userId, text, requestId);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(out.contentType());
            response.setHeader("X-Tts-Provider", out.provider());
            response.setHeader("X-Tts-Model", out.modelCode());
            response.setHeader("X-Tts-Mock", String.valueOf(out.mock()));
            // 非流式合成如实标注（先生成完整音频再分块写出——仅验证分块传输协议）
            response.setHeader("X-Tts-Streaming", String.valueOf(out.streaming()));
            response.setContentLength(out.audio().length);
            response.getOutputStream().write(out.audio());
            response.flushBuffer();
        } catch (com.kzai.common.exception.BizException bex) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"msg\":\"" + jsonEscape(bex.getMessage())
                    + "\",\"data\":null,\"requestId\":\"" + requestId + "\",\"success\":false}");
        }
    }

    /** 语音能力描述（前端初始化/降级判断用） */
    @GetMapping("/audio/capabilities")
    public R<Map<String, Object>> capabilities() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean enabled = speechService.voiceEnabled();
        boolean mock = speechService.mockMode();
        m.put("enabled", enabled);
        m.put("mock", mock);
        m.put("sttStreaming", false);
        m.put("ttsStreaming", false);
        m.put("sttMaxBytes", speechService.sttMaxBytes());
        m.put("sttMaxSeconds", speechService.sttMaxSeconds());
        m.put("ttsMaxChars", speechService.ttsMaxChars());
        m.put("notice", !enabled ? "语音功能已关闭"
                : mock ? "MOCK 语音演示，非真实语音识别/合成"
                : ""); // M15.22：移除第三方解释性提示（REAL 模式无提示文案）
        return R.ok(m);
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
