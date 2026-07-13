package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 会话压缩摘要实体类
 * 对应数据库表：chat_message_summary
 */
@Data
@Builder
public class ChatMessageSummary {
    private String id;

    private String sessionId;

    private String summaryContent;

    private String messageRange;

    private Integer originalMessageCount;

    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        ChatMessageSummary other = (ChatMessageSummary) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getSessionId() == null ? other.getSessionId() == null : this.getSessionId().equals(other.getSessionId()))
            && (this.getSummaryContent() == null ? other.getSummaryContent() == null : this.getSummaryContent().equals(other.getSummaryContent()))
            && (this.getMessageRange() == null ? other.getMessageRange() == null : this.getMessageRange().equals(other.getMessageRange()))
            && (this.getOriginalMessageCount() == null ? other.getOriginalMessageCount() == null : this.getOriginalMessageCount().equals(other.getOriginalMessageCount()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getSessionId() == null) ? 0 : getSessionId().hashCode());
        result = prime * result + ((getSummaryContent() == null) ? 0 : getSummaryContent().hashCode());
        result = prime * result + ((getMessageRange() == null) ? 0 : getMessageRange().hashCode());
        result = prime * result + ((getOriginalMessageCount() == null) ? 0 : getOriginalMessageCount().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [" +
                "Hash = " + hashCode() +
                ", id=" + id +
                ", sessionId=" + sessionId +
                ", summaryContent=" + summaryContent +
                ", messageRange=" + messageRange +
                ", originalMessageCount=" + originalMessageCount +
                ", createdAt=" + createdAt +
                "]";
    }
}
