package io.github.jerryt92.j2agent.config.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * SimpleRag 后台同步线程池配置。
 */
@Configuration
public class SimpleRagTaskConfig {
    public static final String SIMPLE_RAG_TASK_EXECUTOR = "simpleRagTaskExecutor";

    /**
     * 提供单线程串行执行器，避免多个 SimpleRag collection 同时操作向量库。
     */
    @Bean(SIMPLE_RAG_TASK_EXECUTOR)
    public TaskExecutor simpleRagTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("simple-rag-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
        return executor;
    }
}
