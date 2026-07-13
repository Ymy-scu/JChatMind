package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.dto.ChunkBgeM3DTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentParserService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.rag.Chunk;
import com.kama.jchatmind.service.rag.ParsedChunk;
import com.kama.jchatmind.service.rag.TokenAwareSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final List<DocumentParserService> documentParsers;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObjectMapper objectMapper;
    private final TokenAwareSplitter tokenAwareSplitter;

    public DocumentFacadeServiceImpl(
            DocumentMapper documentMapper,
            DocumentConverter documentConverter,
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            List<DocumentParserService> documentParsers,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            ObjectMapper objectMapper,
            TokenAwareSplitter tokenAwareSplitter) {
        this.documentMapper = documentMapper;
        this.documentConverter = documentConverter;
        this.documentStorageService = documentStorageService;
        this.markdownParserService = markdownParserService;
        this.documentParsers = documentParsers;
        this.ragService = ragService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.objectMapper = objectMapper;
        this.tokenAwareSplitter = tokenAwareSplitter;
    }

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 删除关联的 chunks
        chunkBgeM3Mapper.deleteByDocId(documentId);

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
    }

    @Override
    public void parseDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() == null || documentDTO.getMetadata().getFilePath() == null) {
                throw new BizException("文档文件路径不存在");
            }
            String filePath = documentDTO.getMetadata().getFilePath();
            String filetype = document.getFiletype();

            chunkBgeM3Mapper.deleteByDocId(documentId);

            String filename = document.getFilename();
            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                processMarkdownDocument(document.getKbId(), documentId, filename, filePath);
            } else {
                processDocumentWithParser(document.getKbId(), documentId, filename, filePath, filetype);
            }
        } catch (JsonProcessingException e) {
            throw new BizException("解析文档时序列化错误: " + e.getMessage());
        }
    }

    /**
     * 使用 DocumentParserService 处理非 Markdown 文档
     */
    private void processDocumentWithParser(String kbId, String documentId, String filename, String filePath, String filetype) {
        try {
            DocumentParserService parser = findParser(filetype);
            if (parser == null) {
                throw new BizException("不支持的文件类型: " + filetype);
            }

            log.info("开始处理文档: kbId={}, documentId={}, fileType={}, parser={}", kbId, documentId, filetype, parser.getClass().getSimpleName());

            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                List<DocumentParserService.DocumentSection> sections = parser.parseDocument(inputStream, filetype);

                if (sections.isEmpty()) {
                    log.warn("文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                saveChunks(kbId, documentId, filename, sections);
                log.info("文档处理完成: documentId={}, 共 {} 个 section", documentId, sections.size());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("处理文档失败: documentId={}", documentId, e);
            throw new BizException("处理文档失败: " + e.getMessage());
        }
    }

    /**
     * 查找支持指定文件类型的解析器
     */
    private DocumentParserService findParser(String filetype) {
        for (DocumentParserService parser : documentParsers) {
            if (parser.supports(filetype)) {
                return parser;
            }
        }
        return null;
    }

    /**
     * 将解析器输出的 sections 转成 {@link ParsedChunk}，交给
     * {@link TokenAwareSplitter} 做二次切分后统一入库。
     *
     * <p>核心修复：</p>
     * <ul>
     *   <li>Embedding 使用最终入库的 {@code content} 生成，而不是 title</li>
     *   <li>写入 filename / pageNumber / headingPath / chunkIndex / tokenCount 元数据</li>
     *   <li>空正文的 chunk 跳过入库，仅记录 WARN 日志</li>
     * </ul>
     */
    private void saveChunks(String kbId, String documentId, String filename,
                            List<DocumentParserService.DocumentSection> sections) {
        LocalDateTime now = LocalDateTime.now();

        // 1) sections → ParsedChunk（优先使用解析器给出的 headingPath / pageNumber，
        //    退化时回落到 "最新 title 覆盖" 策略）
        List<ParsedChunk> parsed = new ArrayList<>(sections.size());
        String fallbackHeading = null;
        for (DocumentParserService.DocumentSection section : sections) {
            String title = section.getTitle();
            String content = section.getContent();
            String headingPath = section.getHeadingPath();
            Integer pageNumber = section.getPageNumber();

            if (title != null && !title.isBlank()) {
                fallbackHeading = title.trim();
            }
            if (content == null || content.isBlank()) {
                continue;
            }

            String effectiveHeading = (headingPath != null && !headingPath.isBlank())
                    ? headingPath
                    : fallbackHeading;

            parsed.add(ParsedChunk.builder()
                    .content(content)
                    .headingPath(effectiveHeading)
                    .pageNumber(pageNumber)
                    .build());
        }

        if (parsed.isEmpty()) {
            log.warn("文档没有可入库的正文: documentId={}", documentId);
            return;
        }

        // 2) TokenAwareSplitter 二次切分
        List<Chunk> finalChunks = tokenAwareSplitter.split(parsed);
        if (finalChunks.isEmpty()) {
            log.warn("Splitter 输出为空: documentId={}", documentId);
            return;
        }

        int inserted = 0;
        for (Chunk c : finalChunks) {
            String content = c.getContent();
            if (content == null || content.isBlank()) {
                log.warn("跳过空正文 chunk: documentId={}, chunkIndex={}", documentId, c.getChunkIndex());
                continue;
            }
            try {
                // 3) 对最终 content 做 embedding（关键修复）
                float[] embedding = ragService.embed(content);

                ChunkBgeM3DTO.MetaData metaData = new ChunkBgeM3DTO.MetaData();
                metaData.setTitle(c.getHeadingPath());
                metaData.setHeadingLevel(1);
                metaData.setSortOrder(c.getChunkIndex());
                String metadataJson = objectMapper.writeValueAsString(metaData);

                ChunkBgeM3 chunk = ChunkBgeM3.builder()
                        .kbId(kbId)
                        .docId(documentId)
                        .content(content)
                        .metadata(metadataJson)
                        .embedding(embedding)
                        .filename(filename)
                        .pageNumber(c.getPageNumber())
                        .headingPath(c.getHeadingPath())
                        .chunkIndex(c.getChunkIndex())
                        .tokenCount(c.getTokenCount())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();

                int result = chunkBgeM3Mapper.insert(chunk);
                if (result > 0) {
                    inserted++;
                }
            } catch (Exception e) {
                log.error("创建 chunk 失败: documentId={}, chunkIndex={}", documentId, c.getChunkIndex(), e);
            }
        }
        log.info("文档入库完成: documentId={}, 共生成 {} 个 chunk", documentId, inserted);
    }

    /**
     * 处理 Markdown 文档，解析并生成 chunks
     */
    private void processMarkdownDocument(String kbId, String documentId, String filename, String filePath) {
        try {
            log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

            // 从保存的文件路径读取文件
            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                // 解析 Markdown 文件
                List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);

                if (sections.isEmpty()) {
                    log.warn("Markdown 文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                // 转换为统一的 DocumentSection 格式（保留 headingPath）
                List<DocumentParserService.DocumentSection> documentSections = new ArrayList<>();
                for (MarkdownParserService.MarkdownSection section : sections) {
                    documentSections.add(new DocumentParserService.DocumentSection(
                            section.getTitle(),
                            section.getContent(),
                            section.getHeadingLevel(),
                            section.getHeadingPath(),
                            null
                    ));
                }

                saveChunks(kbId, documentId, filename, documentSections);
                log.info("Markdown 文档处理完成: documentId={}", documentId);
            }
        } catch (Exception e) {
            log.error("处理 Markdown 文档失败: documentId={}", documentId, e);
            // 不抛出异常，避免影响文档上传流程
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
