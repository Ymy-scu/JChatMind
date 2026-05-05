package com.kama.jchatmind.model.request;

import lombok.Data;
import java.util.List;

@Data
public class MergeChunksRequest {
    private List<String> chunkIds;
    private String newTitle;
}
