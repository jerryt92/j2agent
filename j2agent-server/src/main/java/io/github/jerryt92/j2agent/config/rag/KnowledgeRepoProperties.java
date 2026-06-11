package io.github.jerryt92.j2agent.config.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库文件仓配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "j2agent.knowledge.repo")
public class KnowledgeRepoProperties {
    /**
     * 知识库根目录。
     */
    private String rootPath = "";
    /**
     * 是否开启目录监听。
     */
    private boolean watchEnabled = true;
    /**
     * 正文 content_segment 滑动窗口单段最大字符数。
     */
    private int contentSegmentChars;
    /**
     * 正文 content_segment 相邻段重叠字符数。
     */
    private int contentSegmentOverlapChars;
}
