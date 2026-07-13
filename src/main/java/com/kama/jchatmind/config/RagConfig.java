package com.kama.jchatmind.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAG 相关 Bean 装配：
 * <ul>
 *   <li>启用 {@link RagProperties} 配置绑定</li>
 *   <li>提供 {@code hybridExecutor}，用于向量与 BM25 并行召回</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    /**
     * 并行召回执行器：向量召回与 BM25 召回并发提交，减少一半链路耗时。
     *
     * <p>核心线程数 = 4：足够覆盖向量 + BM25 + rerank 预取的常见场景；
     * 命名以 {@code rag-hybrid-} 前缀便于线上排查。</p>
     */
    @Bean(name = "hybridExecutor", destroyMethod = "shutdown")
    public ExecutorService hybridExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("rag-hybrid-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }
}
