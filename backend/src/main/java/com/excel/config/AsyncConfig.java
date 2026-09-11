package com.excel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 导入任务异步执行器：
 * 上传请求只落盘 + 建任务，解析在独立线程池中流式进行，HTTP 立即返回任务编号。
 * 核心 2 / 最大 4：导入是 IO+DB 密集型，避免过多并发把 JVM 堆和 DB 打满。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String IMPORT_EXECUTOR = "importExecutor";

    @Bean(IMPORT_EXECUTOR)
    public ExecutorService importExecutor() {
        return new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "excel-import-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
