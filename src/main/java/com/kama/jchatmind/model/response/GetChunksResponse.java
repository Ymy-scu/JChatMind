package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.ChunkVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetChunksResponse {
    private ChunkVO[] chunks;
}
