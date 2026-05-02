package com.kama.jchatmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置类
 * 用于配置和管理应用中的异步执行器，支持@Async注解的异步方法调用
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 创建并配置线程池任务执行器
     * 该执行器用于处理所有标记为@Async的异步方法
     *
     * @return 配置好的线程池执行器
     */
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 设置核心线程数：线程池维护的最小线程数量
        executor.setCorePoolSize(4);

        // 设置最大线程数：线程池允许创建的最大线程数量
        executor.setMaxPoolSize(10);

        // 设置队列容量：当核心线程都在忙碌时，新任务会放入等待队列
        executor.setQueueCapacity(100);

        // 设置线程名称前缀：便于调试和监控时识别线程来源
        executor.setThreadNamePrefix("async-event-");

        // 初始化线程池
        executor.initialize();

        return executor;
    }
}
