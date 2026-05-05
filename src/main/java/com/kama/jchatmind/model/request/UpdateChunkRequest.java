package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class UpdateChunkRequest {
    private String content;
    private String title;
    private Integer headingLevel;
    private Integer sortOrder;
}
