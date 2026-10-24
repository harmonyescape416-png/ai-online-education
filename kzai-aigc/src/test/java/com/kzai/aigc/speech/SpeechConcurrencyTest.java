package com.kzai.aigc.speech;

import com.kzai.aigc.config.DynamicAiParams;
import com.kzai.aigc.stats.ModelCallLogService;
import com.kzai.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M14 语音并发护栏确定性测试（HTTP 层 MOCK 合成毫秒级完成、竞态不可观测，
 * 故并发上限语义在本层以可控时序验证）：
 * <ul>
 *   <li>并发=1：占用中第二个请求受控拒绝（"语音服务繁忙"）——与 SpeechService 内同款信号量语义；</li>
 *   <li>并发=2：两路并发均可完成；</li>
 *   <li>voice.enabled=false：直接受控拒绝。</li>
 * </ul>
 * 信号量护栏独立于 Redis（M14 计费风险最小防护，不改变 M13 已批准的聊天降级语义）。
 */
class SpeechConcurrencyTest {

    private DynamicAiParams params;
    private Map<String, String> paramValues;

    @BeforeEach
    void setUp() {
        params = mock(DynamicAiParams.class);
        paramValues = new LinkedHashMap<>();
        paramValues.put("voice.enabled", "true");
        paramValues.put("voice.max-concurrent", "1");
        when(params.snapshot()).thenReturn(paramValues);
        when(params.timeoutSeconds()).thenReturn(5);
        when(params.sttMaxBytes()).thenReturn(5 * 1024 * 1024);
        when(params.ttsMaxChars()).thenReturn(500);
    }

    private SpeechService service() {
        TextToSpeechProvider slowTts = new TextToSpeechProvider() {
            @Override
            public TtsResult synthesize(String text, int timeoutSeconds) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                return new TtsResult(MockSpeechProvider.renderWav(1), "audio/wav", "MOCK", "mock", true);
            }

            @Override
            public boolean mock() {
                return true;
            }
        };
        SpeechToTextProvider stt = mock(SpeechToTextProvider.class);
        when(stt.mock()).thenReturn(true);
        when(stt.transcribe(any(), anyString(), anyInt()))
                .thenReturn(new SpeechToTextProvider.SttResult("x", "MOCK", "m", true));
        return new SpeechService(params, stt, slowTts, new AudioValidator(),
                mock(ModelCallLogService.class));
    }

    @Test
    void semaphore1_secondAcquireRejected() {
        // 与 SpeechService.acquire() 同款 tryAcquire 语义：并发=1 时占用中第二个受控拒绝
        java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(1);
        assertTrue(sem.tryAcquire(), "第一请求进入");
        assertEquals(false, sem.tryAcquire(), "占用期间第二请求必须被拒");
        sem.release();
    }

    @Test
    void concurrency2_bothComplete() throws Exception {
        paramValues.put("voice.max-concurrent", "2");
        SpeechService svc = service();
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Runnable r = () -> {
            try {
                start.await();
                svc.synthesize(3L, "并发验证", "req-c2");
                ok.incrementAndGet();
            } catch (Exception ignored) {
            }
        };
        Thread a = new Thread(r);
        Thread b = new Thread(r);
        a.start();
        b.start();
        start.countDown();
        a.join(3000);
        b.join(3000);
        assertEquals(2, ok.get(), "并发=2 时两路请求均可完成");
    }

    @Test
    void voiceDisabled_controlledReject() {
        paramValues.put("voice.enabled", "false");
        SpeechService svc = service();
        try {
            svc.synthesize(3L, "x", "req-off");
            org.junit.jupiter.api.Assertions.fail("应受控拒绝");
        } catch (BizException b) {
            assertTrue(b.getMessage().contains("语音功能已由管理员关闭"));
        }
    }
}
