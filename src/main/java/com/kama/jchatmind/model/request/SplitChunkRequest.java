package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class SplitChunkRequest {
    private String firstTitle;
    private Integer splitPosition;
}
