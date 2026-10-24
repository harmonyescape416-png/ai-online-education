package com.kzai.aigc.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.Map;

/**
 * M10 排障自检：启动时打印 RequestMappingHandlerMapping 注册的端点总数
 * 与 /internal/ai 前缀端点（验证 controller mapping 是否注册）。
 */
@Slf4j
@Component
public class MappingSelfCheck implements ApplicationRunner {

    @Autowired
    private Map<String, RequestMappingInfoHandlerMapping> handlerMappings;

    @Override
    public void run(ApplicationArguments args) {
        handlerMappings.forEach((name, hm) -> {
            int total = hm.getHandlerMethods().size();
            long internalAi = hm.getHandlerMethods().keySet().stream()
                    .filter(i -> String.valueOf(i.getPatternValues()).contains("/internal/ai"))
                    .count();
            log.info("[mapping-check] {} total={} internalAi={}", name, total, internalAi);
            hm.getHandlerMethods().forEach((info, method) -> {
                String patterns = String.valueOf(info.getPatternValues());
                if (log.isDebugEnabled()) {
                    log.debug("[mapping-check]   {} -> {}", patterns,
                            ((HandlerMethod) method).getBeanType().getSimpleName());
                }
                // 路径前缀拼接产生双斜杠会在运行期表现为 404，启动即告警
                if (patterns.contains("//")) {
                    log.warn("[mapping-check] 路径存在双斜杠，请求将无法匹配：{}", patterns);
                }
            });
            if (internalAi > 0) {
                hm.getHandlerMethods().forEach((info, method) -> {
                    if (String.valueOf(info.getPatternValues()).contains("/internal/ai")) {
                        log.info("[mapping-check]   {} -> {}", info, ((HandlerMethod) method).getMethod());
                    }
                });
            }
        });
    }
}
