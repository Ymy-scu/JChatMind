package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.MergeChunksRequest;
import com.kama.jchatmind.model.request.SplitChunkRequest;
import com.kama.jchatmind.model.request.UpdateChunkRequest;
import com.kama.jchatmind.model.response.GetChunksResponse;
import com.kama.jchatmind.model.vo.ChunkVO;

public interface ChunkService {
    GetChunksResponse getChunksByDocId(String docId);

    ChunkVO updateChunk(String chunkId, UpdateChunkRequest request);

    void deleteChunk(String chunkId);

    ChunkVO[] splitChunk(String chunkId, SplitChunkRequest request);

    ChunkVO mergeChunks(MergeChunksRequest request);

    void reEmbedChunks(String docId);
}
