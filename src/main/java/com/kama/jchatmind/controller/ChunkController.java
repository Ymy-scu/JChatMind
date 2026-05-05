package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.MergeChunksRequest;
import com.kama.jchatmind.model.request.SplitChunkRequest;
import com.kama.jchatmind.model.request.UpdateChunkRequest;
import com.kama.jchatmind.model.response.GetChunksResponse;
import com.kama.jchatmind.model.vo.ChunkVO;
import com.kama.jchatmind.service.ChunkService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChunkController {

    private final ChunkService chunkService;

    @GetMapping("/documents/{docId}/chunks")
    public ApiResponse<GetChunksResponse> getChunksByDocId(@PathVariable String docId) {
        return ApiResponse.success(chunkService.getChunksByDocId(docId));
    }

    @PutMapping("/chunks/{chunkId}")
    public ApiResponse<ChunkVO> updateChunk(@PathVariable String chunkId, @RequestBody UpdateChunkRequest request) {
        return ApiResponse.success(chunkService.updateChunk(chunkId, request));
    }

    @DeleteMapping("/chunks/{chunkId}")
    public ApiResponse<Void> deleteChunk(@PathVariable String chunkId) {
        chunkService.deleteChunk(chunkId);
        return ApiResponse.success();
    }

    @PostMapping("/chunks/{chunkId}/split")
    public ApiResponse<ChunkVO[]> splitChunk(@PathVariable String chunkId, @RequestBody SplitChunkRequest request) {
        return ApiResponse.success(chunkService.splitChunk(chunkId, request));
    }

    @PostMapping("/chunks/merge")
    public ApiResponse<ChunkVO> mergeChunks(@RequestBody MergeChunksRequest request) {
        return ApiResponse.success(chunkService.mergeChunks(request));
    }

    @PostMapping("/documents/{docId}/chunks/re-embed")
    public ApiResponse<Void> reEmbedChunks(@PathVariable String docId) {
        chunkService.reEmbedChunks(docId);
        return ApiResponse.success();
    }
}
