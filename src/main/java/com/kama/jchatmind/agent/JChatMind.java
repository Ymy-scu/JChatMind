package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMemoryCompressionService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.RedisChatMemoryService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.rag.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * JChatMind 智能代理核心引擎
 *
 * <p>这是一个基于 Spring AI 的 ReAct（Reasoning + Acting）模式的智能代理实现。
 * 它支持以下核心功能：</p>
 *
 * <ul>
 *   <li><b>工具调用</b>：自动识别并调用外部工具获取信息</li>
 *   <li><b>RAG检索增强</b>：从知识库中检索相关信息以增强回答质量</li>
 *   <li><b>对话记忆管理</b>：维护对话历史，支持上下文理解</li>
 *   <li><b>流式响应</b>：通过 SSE 实时推送 AI 生成的内容片段</li>
 *   <li><b>对话压缩</b>：定期压缩长对话历史，优化性能</li>
 * </ul>
 *
 * <p><b>工作流程（ReAct循环）：</b></p>
 * <ol>
 *   <li><b>Think阶段</b>：分析用户问题，决定是否需要调用工具或检索知识库</li>
 *   <li><b>Execute阶段</b>：执行工具调用，获取结果并添加到对话历史</li>
 *   <li><b>总结阶段</b>：基于工具返回结果生成自然语言回答</li>
 * </ol>
 *
 * @author Kama
 * @version 1.0
 */
@Slf4j
public class JChatMind {

    /** 代理唯一标识符 */
    private String agentId;

    /** 代理名称 */
    private String name;

    /** 代理描述信息 */
    private String description;

    /** 系统提示词，定义代理的行为和角色 */
    private String systemPrompt;

    /** Spring AI ChatClient 实例，用于与大语言模型交互 */
    private ChatClient chatClient;

    /** 代理当前状态（IDLE/THINKING/EXECUTING/FINISHED/ERROR） */
    private AgentState agentState;

    /** 可用的工具回调列表，如天气查询、数据库查询等 */
    private List<ToolCallback> availableTools;

    /** 可用的知识库列表，用于 RAG 检索增强 */
    private List<KnowledgeBaseDTO> availableKbs;

    /** 工具调用管理器，负责执行工具调用并处理结果 */
    private ToolCallingManager toolCallingManager;

    /** 对话记忆存储，维护会话级别的对话历史 */
    private ChatMemory chatMemory;

    /** 当前聊天会话 ID，用于区分不同用户的对话 */
    private String chatSessionId;

    /** 最大执行步数，防止无限循环 */
    private static final Integer MAX_STEPS = 20;

    /** 默认最大消息数量，用于限制对话历史长度 */
    private static final Integer DEFAULT_MAX_MESSAGES = 100;

    /** 聊天选项配置，包含工具调用相关设置 */
    private ChatOptions chatOptions;

    /** SSE 服务，用于向客户端推送实时消息 */
    private SseService sseService;

    /** 聊天消息转换器，负责 DTO/VO/Entity 之间的转换 */
    private ChatMessageConverter chatMessageConverter;

    /** 聊天消息门面服务，负责消息的持久化操作 */
    private ChatMessageFacadeService chatMessageFacadeService;

    /** 最后一次 ChatClient 的响应结果 */
    private ChatResponse lastChatResponse;

    /** RAG 服务，提供向量嵌入和相似度搜索功能 */
    private RagService ragService;

    /** Redis 聊天记忆服务，将消息缓存到 Redis */
    private RedisChatMemoryService redisChatMemoryService;

    /** 对话记忆压缩服务，用于压缩长对话历史 */
    private ChatMemoryCompressionService chatMemoryCompressionService;

    /**
     * 本轮 RAG 命中的引用溯源结果，供 SSE {@code AI_REFERENCES} 与
     * 助手消息持久化使用。运行结束或下轮 think() 开始前重置。
     */
    private List<RetrievedChunk> currentReferences = new ArrayList<>();

    /**
     * 无参构造函数
     */
    public JChatMind() {
    }

    /**
     * 全参构造函数，初始化 JChatMind 代理的所有组件
     *
     * @param agentId 代理唯一标识
     * @param name 代理名称
     * @param description 代理描述
     * @param systemPrompt 系统提示词，定义代理行为
     * @param chatClient Spring AI ChatClient 实例
     * @param maxMessages 最大对话消息数量，null 时使用默认值 100
     * @param memory 初始对话历史消息列表
     * @param availableTools 可用工具回调列表
     * @param availableKbs 可用知识库列表
     * @param chatSessionId 聊天会话 ID
     * @param sseService SSE 推送服务
     * @param chatMessageFacadeService 消息门面服务
     * @param chatMessageConverter 消息转换器
     * @param ragService RAG 检索增强服务
     * @param redisChatMemoryService Redis 记忆存储服务
     * @param chatMemoryCompressionService 对话压缩服务
     */
    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     RagService ragService,
                     RedisChatMemoryService redisChatMemoryService,
                     ChatMemoryCompressionService chatMemoryCompressionService
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClient;
        this.availableTools = availableTools;
        this.availableKbs = availableKbs;
        this.chatSessionId = chatSessionId;
        this.sseService = sseService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.ragService = ragService;
        this.redisChatMemoryService = redisChatMemoryService;
        this.chatMemoryCompressionService = chatMemoryCompressionService;
        this.agentState = AgentState.IDLE;

        // 初始化对话记忆窗口，设置最大消息数量
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();

        // 加载历史对话消息
        this.chatMemory.add(chatSessionId, memory);

        // 如果有系统提示词，将其作为系统消息添加到对话历史
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 配置聊天选项，禁用内部工具自动执行，改为手动控制
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 初始化工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    /**
     * 确保知识库上下文已加载到对话记忆中。
     *
     * <p>新链路（一站式）：</p>
     * <ol>
     *   <li>提取用户最新的问题</li>
     *   <li>调用 {@link RagService#retrieve(String, String)}（内部完成
     *       向量 → BM25 → RRF → rerank）拿到 {@link RetrievedChunk}</li>
     *   <li>把 top-K 正文拼成 <b>一条</b> SystemMessage 挂到 chatMemory
     *       （避免每轮 append 累积上下文长度）</li>
     *   <li>把 chunk 元数据缓存到 {@link #currentReferences}，供 SSE
     *       {@code AI_REFERENCES} 与助手消息 metadata 使用</li>
     * </ol>
     *
     * <p><b>与旧实现的对比：</b></p>
     * <ul>
     *   <li>不再对历史 KnowledgeTool 返回做二次 embedding 相似度对比 —
     *       hybrid + rerank 本身已保证跨轮检索质量，反而 embed 历史文本
     *       是主要延迟来源。</li>
     *   <li>不再对每个知识库单独 append 一条 SystemMessage，而是合并
     *       为一条 "【知识库检索结果】" 系统消息。</li>
     * </ul>
     */
    private void ensureKnowledgeContext() {
        // 每一轮都先重置引用列表，避免上一轮遗留
        this.currentReferences = new ArrayList<>();

        // 如果没有配置知识库或 RAG 服务，直接返回
        if (availableKbs == null || availableKbs.isEmpty() || ragService == null) {
            return;
        }

        // 获取当前对话历史
        List<Message> messages = chatMemory.get(chatSessionId);

        // 提取用户最后一条消息作为当前查询
        String currentQuery = extractLastUserMessage(messages);
        if (!StringUtils.hasText(currentQuery)) {
            return;
        }

        // 对所有可用知识库并行/串行调用 retrieve；按 (documentId, chunkIndex) 去重
        LinkedHashMap<String, RetrievedChunk> deduped = new LinkedHashMap<>();
        for (KnowledgeBaseDTO kb : availableKbs) {
            try {
                List<RetrievedChunk> hits = ragService.retrieve(kb.getId(), currentQuery);
                if (hits == null || hits.isEmpty()) {
                    continue;
                }
                for (RetrievedChunk c : hits) {
                    String key = referenceKey(c);
                    deduped.putIfAbsent(key, c);
                }
                log.info("Retrieved {} chunks from kb: {}", hits.size(), kb.getId());
            } catch (Exception e) {
                log.warn("Failed to retrieve knowledge from kb: {}", kb.getId(), e);
            }
        }

        if (deduped.isEmpty()) {
            return;
        }

        // 缓存本轮引用元数据
        this.currentReferences = new ArrayList<>(deduped.values());

        // 只拼一条 SystemMessage，避免 chatMemory 累积
        String resultContent = this.currentReferences.stream()
                .map(this::formatChunkForPrompt)
                .collect(Collectors.joining("\n\n"));
        String knowledgeContext = "【知识库检索结果】\n" + resultContent;
        chatMemory.add(chatSessionId, new SystemMessage(knowledgeContext));

        log.info("Pre-retrieved {} unique chunks for query: {}",
                this.currentReferences.size(), currentQuery);
    }

    /**
     * 构造 chunk 在 chatMemory 里出现时的展示形式，尽量把 heading/来源
     * 传达给 LLM，帮助其在回答中引用来源。
     */
    private String formatChunkForPrompt(RetrievedChunk c) {
        StringBuilder sb = new StringBuilder();
        String source = c.getFilename();
        String heading = c.getHeadingPath();
        Integer page = c.getPageNumber();

        if (StringUtils.hasText(source) || StringUtils.hasText(heading) || page != null) {
            sb.append("[");
            if (StringUtils.hasText(source)) sb.append(source);
            if (page != null) sb.append(" p.").append(page);
            if (StringUtils.hasText(heading)) sb.append(" · ").append(heading);
            sb.append("]\n");
        }
        if (c.getContent() != null) {
            sb.append(c.getContent());
        }
        return sb.toString();
    }

    private String referenceKey(RetrievedChunk c) {
        String doc = c.getDocumentId() == null ? "-" : c.getDocumentId();
        int idx = c.getChunkIndex() == null ? -1 : c.getChunkIndex();
        return doc + "#" + idx;
    }

    /**
     * 从对话历史中提取最后一条用户消息
     *
     * @param messages 对话历史消息列表
     * @return 最后一条用户消息文本，如果没有则返回 null
     */
    private String extractLastUserMessage(List<Message> messages) {
        // 从后往前遍历，找到第一条用户消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage) {
                return ((UserMessage) msg).getText();
            }
        }
        return null;
    }

    /**
     * 记录工具调用的详细信息到日志
     *
     * @param toolCalls 工具调用列表
     */
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        // 格式化每个工具调用的信息
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));

        // 输出格式化的工具调用日志
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    /**
     * 持久化聊天消息并通过 SSE 推送给客户端
     *
     * <p>此方法完成以下任务：</p>
     * <ol>
     *   <li>将消息保存到数据库</li>
     *   <li>将消息缓存到 Redis</li>
     *   <li>通过 SSE 推送给前端</li>
     *   <li>检查是否需要压缩对话历史</li>
     * </ol>
     *
     * @param chatMessageDTO 待持久化的聊天消息 DTO
     */
    private void persistAndSend(ChatMessageDTO chatMessageDTO) {
        // 保存消息到数据库，获取生成的消息 ID
        CreateChatMessageResponse saved = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(saved.getChatMessageId());

        // 保存消息到 Redis，用于快速检索和会话恢复
        redisChatMemoryService.addMessage(this.chatSessionId, chatMessageDTO);

        // 转换为 VO 对象
        ChatMessageVO vo = chatMessageConverter.toVO(chatMessageDTO);

        // 构建 SSE 消息
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(vo)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(saved.getChatMessageId())
                        .build())
                .build();

        // 通过 SSE 推送给客户端
        sseService.send(this.chatSessionId, sseMessage);

        // 检查是否需要压缩对话历史
        checkAndCompress();
    }

    /**
     * 检查并执行对话历史压缩
     *
     * <p>压缩策略：每 5 轮对话（10条消息）触发一次压缩，将长对话历史
     * 压缩为摘要，减少上下文长度，提升性能。</p>
     */
    private void checkAndCompress() {
        // 判断是否达到压缩条件
        if (chatMemoryCompressionService.shouldCompress(this.chatSessionId)) {
            log.info("Triggering compression for session: {}", this.chatSessionId);
            try {
                // 执行压缩，生成对话摘要
                String summary = chatMemoryCompressionService.compress(this.chatSessionId, this.chatClient);
                if (summary != null) {
                    log.info("Compression completed for session: {}", this.chatSessionId);
                }
            } catch (Exception e) {
                log.error("Compression failed for session: {}", this.chatSessionId, e);
            }
        }
    }

    /**
     * 发送内容增量片段给客户端
     *
     * <p>用于流式响应，将 AI 生成的内容逐字或逐段推送给前端，
     * 提供更好的用户体验。</p>
     *
     * @param text 内容片段文本
     */
    private void sendContentDelta(String text) {
        SseMessage delta = SseMessage.builder()
                .type(SseMessage.Type.AI_CONTENT_DELTA)
                .payload(SseMessage.Payload.builder()
                        .contentDelta(text)
                        .build())
                .build();
        sseService.send(this.chatSessionId, delta);
    }

    /** references 事件正文最大长度，避免整段文档全量下推浪费带宽 */
    private static final int REFERENCE_SNIPPET_MAX_LEN = 200;

    /**
     * 通过 SSE 推送 references 事件，供前端"参考资料"面板渲染。
     * 出于载荷体积考虑，{@code content} 会被截断为最多 {@link #REFERENCE_SNIPPET_MAX_LEN} 字符。
     */
    private void sendReferences(List<RetrievedChunk> references) {
        List<RetrievedChunk> snippets = new ArrayList<>(references.size());
        for (RetrievedChunk c : references) {
            String content = c.getContent();
            if (content != null && content.length() > REFERENCE_SNIPPET_MAX_LEN) {
                content = content.substring(0, REFERENCE_SNIPPET_MAX_LEN);
            }
            snippets.add(RetrievedChunk.builder()
                    .id(c.getId())
                    .documentId(c.getDocumentId())
                    .filename(c.getFilename())
                    .pageNumber(c.getPageNumber())
                    .headingPath(c.getHeadingPath())
                    .chunkIndex(c.getChunkIndex())
                    .content(content)
                    .score(c.getScore())
                    .build());
        }

        SseMessage refMsg = SseMessage.builder()
                .type(SseMessage.Type.AI_REFERENCES)
                .payload(SseMessage.Payload.builder()
                        .references(snippets)
                        .build())
                .build();
        sseService.send(this.chatSessionId, refMsg);
    }

    /**
     * Think 阶段：思考并决定是否调用工具
     *
     * <p>这是 ReAct 循环的第一个阶段，主要完成：</p>
     * <ol>
     *   <li>加载知识库上下文（如果需要）</li>
     *   <li>构建 Prompt 并调用大语言模型</li>
     *   <li>流式接收 AI 响应并实时推送给客户端</li>
     *   <li>解析响应中的工具调用请求</li>
     *   <li>将 AI 回复和工具调用信息保存到对话历史</li>
     * </ol>
     *
     * @return true 表示需要执行工具调用，false 表示无需工具调用，可以直接结束
     */
    private boolean think() {
        // 确保知识库上下文已加载
        ensureKnowledgeContext();

        // 构建思考阶段的系统提示词
        String thinkPrompt = """
                你可以调用工具获取信息。拿到工具返回结果后，必须用自然语言总结给用户。
                可用的知识库：%s
                当用户的问题与知识库相关时，你必须先调用 KnowledgeTool 工具在知识库中进行检索，然后再回答问题。
                如果提供了知识库ID，请优先使用 KnowledgeTool 进行检索。
                """.formatted(this.availableKbs);

        // 构建 Prompt，包含对话历史和聊天选项
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        // 用于累积完整的 AI 响应内容
        final StringBuilder fullContent = new StringBuilder();

        // 调用 ChatClient，流式获取响应
        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    // 提取当前片段文本
                    String text = response.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        // 累积完整内容
                        fullContent.append(text);
                        // 实时推送内容增量
                        sendContentDelta(text);
                    }
                })
                .blockLast(); // 阻塞等待流结束

        // 确保响应不为空
        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        // 提取 AI 助手消息和工具调用列表
        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        // 构建聊天消息 DTO；如果本轮命中了 RAG 引用，一并挂到 metadata 中持久化
        List<RetrievedChunk> referencesSnapshot = this.currentReferences.isEmpty()
                ? null
                : List.copyOf(this.currentReferences);
        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(fullContent.toString())
                .sessionId(this.chatSessionId)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(toolCalls)
                        .references(referencesSnapshot)
                        .build())
                .build();

        // 持久化并推送消息
        persistAndSend(chatMessageDTO);

        // 若本轮有 RAG 引用，额外推送一条 references SSE 事件，供前端渲染"参考资料"卡片
        if (referencesSnapshot != null && !referencesSnapshot.isEmpty()) {
            sendReferences(referencesSnapshot);
            // 引用只在触发它的那次 think 输出；下一轮 ensureKnowledgeContext 会重置
            this.currentReferences = new ArrayList<>();
        }

        // 将 AI 助手消息添加到对话历史
        this.chatMemory.add(this.chatSessionId, AssistantMessage.builder()
                .content(fullContent.toString())
                .toolCalls(toolCalls)
                .build());

        // 记录工具调用日志
        logToolCalls(toolCalls);

        // 如果存在工具调用，返回 true，否则返回 false
        return !toolCalls.isEmpty();
    }

    /**
     * Execute 阶段：执行工具调用并处理结果
     *
     * <p>这是 ReAct 循环的第二个阶段，主要完成：</p>
     * <ol>
     *   <li>检查是否有工具调用需要执行</li>
     *   <li>使用 ToolCallingManager 执行所有工具调用</li>
     *   <li>将工具返回结果添加到对话历史</li>
     *   <li>通过 SSE 推送工具返回结果给客户端</li>
     *   <li>检查是否收到 terminate 信号，决定是否结束代理</li>
     * </ol>
     */
    private void execute() {
        // 确保上一次响应存在
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        // 如果没有工具调用，直接返回
        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        // 构建 Prompt，包含最新的对话历史
        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        // 执行工具调用，获取执行结果
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        // 提取最后一条工具返回消息
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        // 格式化所有工具的返回结果
        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        // 记录工具调用结果日志
        log.info("工具调用结果：{}", collect);

        // 将工具返回消息添加到对话历史
        this.chatMemory.add(this.chatSessionId, toolResponseMessage);

        // 为每个工具返回结果创建消息并推送
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
            ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.TOOL)
                    .content(toolResponse.responseData())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolResponse(toolResponse)
                            .build())
                    .build();
            persistAndSend(chatMessageDTO);
        }

        // 检查是否有工具要求终止代理运行
        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    /**
     * 执行一步 ReAct 循环
     *
     * <p>根据 Think 阶段的返回值决定：</p>
     * <ul>
     *   <li>如果需要工具调用，则执行 Execute 阶段</li>
     *   <li>如果不需要工具调用，则将代理状态设为 FINISHED</li>
     * </ul>
     */
    private void step() {
        if (think()) {
            // Think 返回 true，需要执行工具调用
            execute();
        } else {
            // Think 返回 false，无需工具调用，结束代理
            agentState = AgentState.FINISHED;
        }
    }

    /**
     * 运行代理，启动 ReAct 循环
     *
     * <p>此方法是代理的主入口，会持续执行以下步骤直到满足结束条件：</p>
     * <ol>
     *   <li>检查代理状态是否为 IDLE，非空闲状态抛出异常</li>
     *   <li>循环执行 step() 方法（Think + Execute）</li>
     *   <li>最多执行 MAX_STEPS 步，防止无限循环</li>
     *   <li>遇到错误或达到最大步数时停止</li>
     *   <li>最终发送 AI_DONE 消息通知客户端</li>
     * </ol>
     *
     * @throws IllegalStateException 如果代理不在 IDLE 状态
     * @throws RuntimeException 如果执行过程中发生错误
     */
    public void run() {
        // 确保代理处于空闲状态
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            // 执行 ReAct 循环，最多 MAX_STEPS 步
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                int currentStep = i + 1;
                step();

                // 检查是否达到最大步数
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }

            // 正常结束
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            // 发生错误，设置错误状态
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        } finally {
            // 无论成功或失败，都发送 AI_DONE 消息
            SseMessage doneMsg = SseMessage.builder()
                    .type(SseMessage.Type.AI_DONE)
                    .payload(SseMessage.Payload.builder().build())
                    .build();
            sseService.send(this.chatSessionId, doneMsg);
        }
    }

    /**
     * 返回代理的字符串表示
     *
     * @return 包含代理基本信息的字符串
     */
    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
