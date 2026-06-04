package io.github.jerryt92.j2agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 后台任务线程池配置
 */
@Configuration
@EnableAsync
public class BackendTaskConfig implements AsyncConfigurer {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            logger.error("Error Occurs in async method:", ex);
        };
    }

    public static final String BACKEND_TASK_EXECUTOR = "aiEdgeBackendTaskExecutor";

    /**
     * 后台任务线程池
     */
    @Bean(BACKEND_TASK_EXECUTOR)
    public TaskExecutor topoBackendTaskExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setThreadNamePrefix("topoBackendTaskExecutor");
        pool.setCorePoolSize(1);//核心线程数
        pool.setMaxPoolSize(1);//最大线程数
        pool.setKeepAliveSeconds(60 * 5);// 设置线程活跃时间（秒）
        pool.setQueueCapacity(0);//线程队列
        pool.initialize();//线程初始化
        return pool;
    }
}