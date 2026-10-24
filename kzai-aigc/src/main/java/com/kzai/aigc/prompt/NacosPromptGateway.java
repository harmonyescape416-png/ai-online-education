package com.kzai.aigc.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Nacos 配置网关（M8，真实 Nacos OpenAPI）。
 * <p>
 * Data-ID / Group 约定（登记 docs/api-extension.md）：
 * <pre>dataId = kzai-ai-prompts.json   group = tj.ai（对齐原始设计 tj.ai 命名空间风格）</pre>
 * 发布使用 Nacos v1 OpenAPI（POST /nacos/v1/cs/configs），写入后立即回读校验，
 * <b>写失败或回读不一致不得假装发布成功</b>。
 * <p>
 * 本地开发环境 Nacos 2.x 未开启鉴权——这是本地运行限制，生产发布前必须开启
 * Nacos 鉴权并加密配置（原始设计 6.2：API Key 经环境变量或 Nacos 加密配置注入）。
 */
@Slf4j
@Component
public class NacosPromptGateway {

    public static final String DATA_ID = "kzai-ai-prompts.json";
    public static final String GROUP = "tj.ai";

    private final RestClient restClient;
    private final String serverAddr;

    public NacosPromptGateway(@Value("${spring.cloud.nacos.discovery.server-addr}") String serverAddr) {
        this.serverAddr = serverAddr;
        this.restClient = RestClient.builder().baseUrl("http://" + serverAddr).build();
    }

    /**
     * 发布配置内容。成功（写入受理 + 有界回读一致）返回 true；失败返回 false，不假装成功。
     * 注：Nacos 2.x v1 OpenAPI 成功时响应体为 "true"（部分版本为 "ok"）；
     * Derby 单机部署下写入持久化存在秒级延迟，故回读采用有界重试（最多约 5 秒）。
     */
    public boolean publish(String content, String operator) {
        try {
            String result = restClient.post()
                    .uri("/nacos/v1/cs/configs")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(formBody(content))
                    .retrieve()
                    .body(String.class);
            String trimmed = String.valueOf(result).trim();
            boolean accepted = "ok".equalsIgnoreCase(trimmed) || "true".equalsIgnoreCase(trimmed);
            if (!accepted) {
                log.warn("[prompt.nacos] 发布响应异常: {}", result);
                return false;
            }
            // 写后回读一致性校验（有界重试）：防止"页面显示成功但 Nacos 实际未生效"
            for (int i = 0; i < 10; i++) {
                String echoed = fetch();
                if (content.equals(echoed)) {
                    return true;
                }
                Thread.sleep(500);
            }
            log.warn("[prompt.nacos] 发布后回读超时（Nacos 已受理，持久化延迟） operator={}", operator);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException ex) {
            log.warn("[prompt.nacos] 发布失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 读取当前配置；不存在或不可达返回 null（404=config not exist 属正常首次状态） */
    public String fetch() {
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/nacos/v1/cs/configs")
                            .queryParam("dataId", DATA_ID)
                            .queryParam("group", GROUP)
                            .build())
                    .retrieve()
                    .body(String.class);
            return body == null || body.isBlank() ? null : body;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            return null; // 首次发布前配置不存在，非错误
        } catch (RuntimeException ex) {
            log.warn("[prompt.nacos] 读取失败: {}", ex.getMessage());
            return null;
        }
    }

    public boolean available() {
        try {
            fetch();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public String getServerAddr() {
        return serverAddr;
    }

    private static String formBody(String content) {
        Map<String, String> form = Map.of(
                "dataId", DATA_ID,
                "group", GROUP,
                "type", "json",
                "content", content);
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(urlEncode(v));
        });
        return sb.toString();
    }

    private static String urlEncode(String v) {
        return java.net.URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
