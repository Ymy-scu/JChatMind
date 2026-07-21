package com.kama.jchatmind.controller;

import com.kama.jchatmind.config.RagProperties;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.service.DocumentFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class DocumentController {

    private final DocumentFacadeService documentFacadeService;
    private final RagProperties ragProperties;

    // 查询所有文档
    @GetMapping("/documents")
    public ApiResponse<GetDocumentsResponse> getDocuments() {
        return ApiResponse.success(documentFacadeService.getDocuments());
    }

    // 根据 kbId 查询文档
    @GetMapping("/documents/kb/{kbId}")
    public ApiResponse<GetDocumentsResponse> getDocumentsByKbId(@PathVariable String kbId) {
        return ApiResponse.success(documentFacadeService.getDocumentsByKbId(kbId));
    }

    // 创建文档（仅创建记录，不上传文件）
    @PostMapping("/documents")
    public ApiResponse<CreateDocumentResponse> createDocument(@RequestBody CreateDocumentRequest request) {
        return ApiResponse.success(documentFacadeService.createDocument(request));
    }

    // 上传文档（上传文件并创建记录）
    @PostMapping("/documents/upload")
    public ApiResponse<CreateDocumentResponse> uploadDocument(
            @RequestParam("kbId") String kbId,
            @RequestParam("file") MultipartFile file) {
        validateUpload(file);
        return ApiResponse.success(documentFacadeService.uploadDocument(kbId, file));
    }

    /**
     * multipart 大小上限由 {@code spring.servlet.multipart.max-file-size} 卡在 Servlet 层，
     * 这里只做后缀白名单校验，防止随手上传 exe/zip 到解析链路。
     */
    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传的文件为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new BizException("文件名不能为空");
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        Set<String> allow = ragProperties.getUpload().getAllowedExtensions().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!allow.contains(ext)) {
            throw new BizException("不支持的文件类型：." + ext + "，允许 " + allow);
        }
    }

    // 删除文档
    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String documentId) {
        documentFacadeService.deleteDocument(documentId);
        return ApiResponse.success();
    }

    // 更新文档
    @PatchMapping("/documents/{documentId}")
    public ApiResponse<Void> updateDocument(@PathVariable String documentId, @RequestBody UpdateDocumentRequest request) {
        documentFacadeService.updateDocument(documentId, request);
        return ApiResponse.success();
    }

    // 解析文档（对已上传的 Markdown 文档进行分段解析并生成 chunks）
    @PostMapping("/documents/{documentId}/parse")
    public ApiResponse<Void> parseDocument(@PathVariable String documentId) {
        documentFacadeService.parseDocument(documentId);
        return ApiResponse.success();
    }
}
