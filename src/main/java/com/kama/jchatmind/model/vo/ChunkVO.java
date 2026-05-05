package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkVO {
    private String id;
    private String kbId;
    private String docId;
    private String content;
    private String title;
    private Integer headingLevel;
    private Integer sortOrder;
}
