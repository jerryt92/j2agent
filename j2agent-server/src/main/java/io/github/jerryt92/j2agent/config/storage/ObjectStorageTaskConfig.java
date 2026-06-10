package io.github.jerryt92.j2agent.config.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ObjectStorageTaskConfig {
    public static final String OBJECT_STORAGE_TASK_EXECUTOR = "objectStorageTaskExecutor";

    @Bean(OBJECT_STORAGE_TASK_EXECUTOR)
    public TaskExecutor objectStorageTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("object-storage-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
        return executor;
    }
}
