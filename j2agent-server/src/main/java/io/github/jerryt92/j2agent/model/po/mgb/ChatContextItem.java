package io.github.jerryt92.j2agent.model.po.mgb;

import java.util.ArrayList;
import java.util.Arrays;

public class ChatContextItem extends ChatContextItemKey {
    private String contextId;

    /** 智能体 ID，与 chat_context_record 对齐。 */
    private String agentId;

    private Integer messageIndex;
    private Integer chatRole;
    private Integer feedback;
    private Long addTime;
    private Integer tokenCount;

    public String getContextId() {
        return contextId;
    }

    public void setContextId(String contextId) {
        this.contextId = contextId == null ? null : contextId.trim();
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId == null ? null : agentId.trim();
    }

    public Integer getMessageIndex() {
        return messageIndex;
    }

    public void setMessageIndex(Integer messageIndex) {
        this.messageIndex = messageIndex;
    }

    public Integer getChatRole() {
        return chatRole;
    }

    public void setChatRole(Integer chatRole) {
        this.chatRole = chatRole;
    }

    public Integer getFeedback() {
        return feedback;
    }

    public void setFeedback(Integer feedback) {
        this.feedback = feedback;
    }

    public Long getAddTime() {
        return addTime;
    }

    public void setAddTime(Long addTime) {
        this.addTime = addTime;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public enum Column {
        messageId("message_id", "messageId", "VARCHAR", false),
        contextId("context_id", "contextId", "VARCHAR", false),
        agentId("agent_id", "agentId", "VARCHAR", false),
        messageIndex("message_index", "messageIndex", "INTEGER", false),
        chatRole("chat_role", "chatRole", "INTEGER", false),
        feedback("feedback", "feedback", "INTEGER", false),
        addTime("add_time", "addTime", "BIGINT", false),
        tokenCount("token_count", "tokenCount", "INTEGER", false),
        content("content", "content", "LONGVARCHAR", false),
        ragInfos("rag_infos", "ragInfos", "LONGVARCHAR", false),
        metaJson("meta_json", "metaJson", "LONGVARCHAR", false);

        private static final String BEGINNING_DELIMITER = "`";
        private static final String ENDING_DELIMITER = "`";
        private final String column;
        private final boolean isColumnNameDelimited;
        private final String javaProperty;
        private final String jdbcType;

        public String value() {
            return this.column;
        }

        public String getValue() {
            return this.column;
        }

        public String getJavaProperty() {
            return this.javaProperty;
        }

        public String getJdbcType() {
            return this.jdbcType;
        }

        Column(String column, String javaProperty, String jdbcType, boolean isColumnNameDelimited) {
            this.column = column;
            this.javaProperty = javaProperty;
            this.jdbcType = jdbcType;
            this.isColumnNameDelimited = isColumnNameDelimited;
        }

        public String desc() {
            return this.getEscapedColumnName() + " DESC";
        }

        public String asc() {
            return this.getEscapedColumnName() + " ASC";
        }

        public static Column[] excludes(Column... excludes) {
            ArrayList<Column> columns = new ArrayList<>(Arrays.asList(Column.values()));
            if (excludes != null && excludes.length > 0) {
                columns.removeAll(new ArrayList<>(Arrays.asList(excludes)));
            }
            return columns.toArray(new Column[]{});
        }

        public static Column[] all() {
            return Column.values();
        }

        public String getEscapedColumnName() {
            if (this.isColumnNameDelimited) {
                return new StringBuilder().append(BEGINNING_DELIMITER).append(this.column).append(ENDING_DELIMITER).toString();
            } else {
                return this.column;
            }
        }

        public String getAliasedEscapedColumnName() {
            return this.getEscapedColumnName();
        }
    }
}
