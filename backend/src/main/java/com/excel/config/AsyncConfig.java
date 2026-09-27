package com.excel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    /**
     * Excel导入专用线程池：与Web线程隔离，避免大文件解析阻塞HTTP请求。
     * 队列满时拒绝提交，由Service层将任务标记为失败并提示稍后重试。
     */
    @Bean(name = "importTaskExecutor")
    public ThreadPoolTaskExecutor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("excel-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
