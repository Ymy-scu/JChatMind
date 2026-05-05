package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.ChunkBgeM3DTO;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.request.MergeChunksRequest;
import com.kama.jchatmind.model.request.SplitChunkRequest;
import com.kama.jchatmind.model.request.UpdateChunkRequest;
import com.kama.jchatmind.model.response.GetChunksResponse;
import com.kama.jchatmind.model.vo.ChunkVO;
import com.kama.jchatmind.service.ChunkService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class ChunkServiceImpl implements ChunkService {

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final RagService ragService;
    private final ObjectMapper objectMapper;

    @Override
    public GetChunksResponse getChunksByDocId(String docId) {
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.selectByDocId(docId);
        List<ChunkVO> result = new ArrayList<>();
        for (ChunkBgeM3 chunk : chunks) {
            result.add(toChunkVO(chunk));
        }

        result.sort(Comparator.comparing(ChunkVO::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())));

        return GetChunksResponse.builder()
                .chunks(result.toArray(new ChunkVO[0]))
                .build();
    }

    @Override
    public ChunkVO updateChunk(String chunkId, UpdateChunkRequest request) {
        ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(chunkId);
        if (chunk == null) {
            throw new BizException("Chunk not found: " + chunkId);
        }

        ChunkBgeM3DTO.MetaData metaData = parseMetaData(chunk.getMetadata());

        if (request.getContent() != null) {
            chunk.setContent(request.getContent());
        }
        if (request.getTitle() != null) {
            metaData.setTitle(request.getTitle());
            float[] embedding = ragService.embed(request.getTitle());
            chunk.setEmbedding(embedding);
        }
        if (request.getHeadingLevel() != null) {
            metaData.setHeadingLevel(request.getHeadingLevel());
        }
        if (request.getSortOrder() != null) {
            metaData.setSortOrder(request.getSortOrder());
        }

        try {
            chunk.setMetadata(objectMapper.writeValueAsString(metaData));
        } catch (JsonProcessingException e) {
            throw new BizException("Failed to serialize metadata: " + e.getMessage());
        }

        chunk.setUpdatedAt(LocalDateTime.now());
        chunkBgeM3Mapper.updateById(chunk);

        return toChunkVO(chunk);
    }

    @Override
    public void deleteChunk(String chunkId) {
        ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(chunkId);
        if (chunk == null) {
            throw new BizException("Chunk not found: " + chunkId);
        }
        chunkBgeM3Mapper.deleteById(chunkId);
    }

    @Override
    public ChunkVO[] splitChunk(String chunkId, SplitChunkRequest request) {
        ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(chunkId);
        if (chunk == null) {
            throw new BizException("Chunk not found: " + chunkId);
        }

        String content = chunk.getContent();
        if (content == null || content.isEmpty()) {
            throw new BizException("Chunk content is empty, cannot split");
        }

        if (content.length() < 3) {
            throw new BizException("Chunk content is too short to split");
        }

        int splitPos = request.getSplitPosition() != null ? request.getSplitPosition() : content.length() / 2;
        if (splitPos <= 0 || splitPos >= content.length()) {
            throw new BizException("Invalid split position: " + splitPos);
        }

        int breakPos = splitPos;
        for (int i = splitPos; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                breakPos = i + 1;
                break;
            }
        }

        String firstContent = content.substring(0, breakPos).trim();
        String secondContent = content.substring(breakPos).trim();

        if (firstContent.isEmpty() || secondContent.isEmpty()) {
            throw new BizException("Cannot split: one part would be empty");
        }

        ChunkBgeM3DTO.MetaData metaData = parseMetaData(chunk.getMetadata());
        String originalTitle = metaData.getTitle();

        LocalDateTime now = LocalDateTime.now();

        chunk.setContent(firstContent);
        chunk.setUpdatedAt(now);
        chunkBgeM3Mapper.updateById(chunk);

        String secondTitle;
        if (request.getFirstTitle() != null && !request.getFirstTitle().trim().isEmpty()) {
            secondTitle = request.getFirstTitle().trim();
        } else if (originalTitle != null && !originalTitle.trim().isEmpty()) {
            secondTitle = originalTitle + " (2)";
        } else {
            secondTitle = firstLineFallback(secondContent, "分段 2");
        }
        float[] secondEmbedding = ragService.embed(secondTitle);

        ChunkBgeM3DTO.MetaData secondMetaData = new ChunkBgeM3DTO.MetaData();
        secondMetaData.setTitle(secondTitle);
        secondMetaData.setHeadingLevel(metaData != null ? metaData.getHeadingLevel() : 1);
        secondMetaData.setSortOrder(metaData != null && metaData.getSortOrder() != null ? metaData.getSortOrder() + 1 : 0);

        try {
            ChunkBgeM3 newChunk = ChunkBgeM3.builder()
                    .kbId(chunk.getKbId())
                    .docId(chunk.getDocId())
                    .content(secondContent)
                    .metadata(objectMapper.writeValueAsString(secondMetaData))
                    .embedding(secondEmbedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            chunkBgeM3Mapper.insert(newChunk);

            updateSortOrders(chunk.getDocId());

            return new ChunkVO[]{toChunkVO(chunk), toChunkVO(newChunk)};
        } catch (JsonProcessingException e) {
            throw new BizException("Failed to serialize metadata: " + e.getMessage());
        }
    }

    @Override
    public ChunkVO mergeChunks(MergeChunksRequest request) {
        List<String> chunkIds = request.getChunkIds();
        if (chunkIds == null || chunkIds.size() < 2) {
            throw new BizException("At least 2 chunks are required to merge");
        }

        String kbId = null;
        String docId = null;
        ChunkBgeM3DTO.MetaData firstMetaData = null;

        for (String cid : chunkIds) {
            ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(cid);
            if (chunk == null) {
                throw new BizException("Chunk not found: " + cid);
            }
            if (kbId == null) {
                kbId = chunk.getKbId();
            }
            if (docId == null) {
                docId = chunk.getDocId();
            }
            if (!docId.equals(chunk.getDocId())) {
                throw new BizException("Chunks must belong to the same document");
            }
            if (firstMetaData == null) {
                firstMetaData = parseMetaData(chunk.getMetadata());
            }
        }

        StringBuilder mergedContent = new StringBuilder();
        for (int i = 0; i < chunkIds.size(); i++) {
            ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(chunkIds.get(i));
            if (i > 0 && mergedContent.length() > 0) {
                mergedContent.append("\n\n");
            }
            mergedContent.append(chunk.getContent() != null ? chunk.getContent() : "");
        }

        String newTitle = request.getNewTitle();
        if (newTitle == null || newTitle.trim().isEmpty()) {
            if (firstMetaData != null && firstMetaData.getTitle() != null && !firstMetaData.getTitle().trim().isEmpty()) {
                newTitle = firstMetaData.getTitle();
            } else {
                newTitle = firstLineFallback(mergedContent.toString(), "合并分段");
            }
        }

        float[] embedding = ragService.embed(newTitle);
        LocalDateTime now = LocalDateTime.now();

        ChunkBgeM3DTO.MetaData newMetaData = new ChunkBgeM3DTO.MetaData();
        newMetaData.setTitle(newTitle);
        newMetaData.setHeadingLevel(firstMetaData != null ? firstMetaData.getHeadingLevel() : 1);
        newMetaData.setSortOrder(firstMetaData != null ? firstMetaData.getSortOrder() : 0);

        try {
            ChunkBgeM3 mergedChunk = ChunkBgeM3.builder()
                    .kbId(kbId)
                    .docId(docId)
                    .content(mergedContent.toString())
                    .metadata(objectMapper.writeValueAsString(newMetaData))
                    .embedding(embedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            chunkBgeM3Mapper.insert(mergedChunk);

            for (String cid : chunkIds) {
                chunkBgeM3Mapper.deleteById(cid);
            }

            updateSortOrders(docId);

            return toChunkVO(mergedChunk);
        } catch (JsonProcessingException e) {
            throw new BizException("Failed to serialize metadata: " + e.getMessage());
        }
    }

    @Override
    public void reEmbedChunks(String docId) {
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.selectByDocId(docId);
        for (ChunkBgeM3 chunk : chunks) {
            ChunkBgeM3DTO.MetaData metaData = parseMetaData(chunk.getMetadata());
            String title = metaData != null && metaData.getTitle() != null ? metaData.getTitle() : chunk.getContent();
            if (title.length() > 500) {
                title = title.substring(0, 500);
            }
            float[] embedding = ragService.embed(title);
            chunk.setEmbedding(embedding);
            chunk.setUpdatedAt(LocalDateTime.now());
            chunkBgeM3Mapper.updateById(chunk);
        }
        log.info("Re-embedded {} chunks for document {}", chunks.size(), docId);
    }

    private ChunkBgeM3DTO.MetaData parseMetaData(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return new ChunkBgeM3DTO.MetaData();
        }
        try {
            return objectMapper.readValue(metadataJson, ChunkBgeM3DTO.MetaData.class);
        } catch (JsonProcessingException e) {
            return new ChunkBgeM3DTO.MetaData();
        }
    }

    private ChunkVO toChunkVO(ChunkBgeM3 chunk) {
        ChunkBgeM3DTO.MetaData metaData = parseMetaData(chunk.getMetadata());
        String title = metaData.getTitle();
        if (title == null || title.trim().isEmpty()) {
            title = firstLineFallback(chunk.getContent(), "未命名");
        }
        return ChunkVO.builder()
                .id(chunk.getId())
                .kbId(chunk.getKbId())
                .docId(chunk.getDocId())
                .content(chunk.getContent())
                .title(title)
                .headingLevel(metaData.getHeadingLevel() != null ? metaData.getHeadingLevel() : 1)
                .sortOrder(metaData.getSortOrder())
                .build();
    }

    private String firstLineFallback(String content, String defaultTitle) {
        if (content != null && !content.isEmpty()) {
            int end = content.indexOf('\n');
            if (end > 0) {
                String firstLine = content.substring(0, end).trim();
                if (!firstLine.isEmpty()) {
                    return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
                }
            } else {
                return content.length() > 80 ? content.substring(0, 80).trim() : content.trim();
            }
        }
        return defaultTitle;
    }

    private void updateSortOrders(String docId) {
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.selectByDocId(docId);
        for (int i = 0; i < chunks.size(); i++) {
            ChunkBgeM3 chunk = chunks.get(i);
            if (chunk.getMetadata() == null || chunk.getMetadata().isEmpty()) {
                continue;
            }
            ChunkBgeM3DTO.MetaData metaData = parseMetaData(chunk.getMetadata());
            metaData.setSortOrder(i);
            try {
                chunk.setMetadata(objectMapper.writeValueAsString(metaData));
            } catch (JsonProcessingException e) {
                continue;
            }
            chunkBgeM3Mapper.updateById(chunk);
        }
    }
}
