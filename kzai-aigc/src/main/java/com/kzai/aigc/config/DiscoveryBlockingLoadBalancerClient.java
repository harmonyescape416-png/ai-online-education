package com.kzai.aigc.config;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M15 REAL 修复：不依赖 Reactor 的 BlockingLoadBalancerClient。
 * <p>
 * 根因（堆栈实证）：真实模型流式链路中，DashScopeChatModel 在 Netty 事件循环线程
 * （reactor-http-nio-*，实现 reactor.blockhound 兼容的 NonBlocking 标记）内联执行工具回调；
 * 默认 BlockingLoadBalancerClient.choose 内部用 Mono.block() 解析实例，
 * 在 NonBlocking 线程上被 Reactor 3.6 BlockingChecks 直接拒绝——
 * 工具审计表现为 searchCourses success=false / SERVICE_UNAVAILABLE。
 * <p>
 * 本实现改用 DiscoveryClient（Nacos）阻塞式取实例 + 简单轮询负载均衡，
 * 全程无 Reactor，任意线程可用。仅作用于 kzai-aigc（唯一在事件循环线程上
 * 同步执行 Feign 工具的服务）；其它服务沿用默认实现不受影响。
 */
public class DiscoveryBlockingLoadBalancerClient implements LoadBalancerClient {

    private final DiscoveryClient discoveryClient;
    private final AtomicLong cursor = new AtomicLong();

    public DiscoveryBlockingLoadBalancerClient(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Override
    public <T> T execute(String serviceId, LoadBalancerRequest<T> request) {
        return execute(serviceId, choose(serviceId), request);
    }

    @Override
    public <T> T execute(String serviceId, ServiceInstance instance, LoadBalancerRequest<T> request) {
        if (instance == null) {
            throw new IllegalStateException("No instances available for " + serviceId);
        }
        try {
            return request.apply(instance);
        } catch (Exception ex) {
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Load balancer request failed for " + serviceId, ex);
        }
    }

    @Override
    @Nullable
    public ServiceInstance choose(String serviceId) {
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        // 轮询负载均衡
        int idx = (int) Math.floorMod(cursor.getAndIncrement(), instances.size());
        return instances.get(idx);
    }

    @Override
    @Nullable
    public ServiceInstance choose(String serviceId, @Nullable @SuppressWarnings("rawtypes") Request request) {
        return choose(serviceId);
    }

    @Override
    public URI reconstructURI(ServiceInstance instance, URI original) {
        return LoadBalancerUriTools.reconstructURI(instance, original);
    }
}
