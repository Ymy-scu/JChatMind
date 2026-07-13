package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.ChatMemoryCompressionService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.RedisChatMemoryService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.UserChatClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JChatMind Agent 工厂类
 *
 * 核心职责：
 * 1. 从数据库加载 Agent 配置
 * 2. 恢复历史对话记忆
 * 3. 解析允许使用的知识库和工具
 * 4. 构建 ToolCallback
 * 5. 创建并返回可运行的 JChatMind 实例
 *
 * 设计模式：工厂模式
 * 作用：封装复杂的 Agent 创建逻辑，实现配置驱动的 Agent 实例化
 */
@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);

    // ==================== 依赖注入 ====================

    /** ChatClient 注册表，用于根据模型名称获取对应的 ChatClient */
    private final ChatClientRegistry chatClientRegistry;

    /** 用户级别的 ChatClient 服务 */
    private final UserChatClientService userChatClientService;

    /** SSE 推送服务，用于实时向前端发送消息 */
    private final SseService sseService;

    /** Agent 数据访问层，用于从数据库查询 Agent 配置 */
    private final AgentMapper agentMapper;

    /** Agent 转换器，用于 Entity 和 DTO 之间的转换 */
    private final AgentConverter agentConverter;

    /** 知识库数据访问层，用于从数据库查询知识库信息 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /** 知识库转换器，用于 Entity 和 DTO 之间的转换 */
    private final KnowledgeBaseConverter knowledgeBaseConverter;

    /** 工具管理服务，用于获取固定工具和可选工具 */
    private final ToolFacadeService toolFacadeService;

    /** 聊天消息服务，用于查询历史对话记录 */
    private final ChatMessageFacadeService chatMessageFacadeService;

    /** 聊天消息转换器，用于 DTO 和 VO 之间的转换 */
    private final ChatMessageConverter chatMessageConverter;

    /** RAG 服务，用于知识检索 */
    private final RagService ragService;

    /** Redis 会话记忆管理服务 */
    private final RedisChatMemoryService redisChatMemoryService;

    /** 会话记忆压缩服务 */
    private final ChatMemoryCompressionService chatMemoryCompressionService;

    /** 运行时 Agent 配置（在 create 方法中设置） */
    private AgentDTO agentConfig;

    /**
     * 构造函数，注入所有必需的依赖
     */
    public JChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            UserChatClientService userChatClientService,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            RagService ragService,
            RedisChatMemoryService redisChatMemoryService,
            ChatMemoryCompressionService chatMemoryCompressionService
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.userChatClientService = userChatClientService;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.ragService = ragService;
        this.redisChatMemoryService = redisChatMemoryService;
        this.chatMemoryCompressionService = chatMemoryCompressionService;
    }

    /**
     * 从数据库加载 Agent 配置
     *
     * @param agentId Agent 的唯一标识
     * @return Agent 实体对象，包含名称、描述、系统提示、模型配置等
     */
    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的历史对话记录恢复成 Spring AI 的 List<Message> 结构
     *
     * 工作流程：
     * 1. 优先从 Redis 加载消息
     * 2. 如果 Redis 没有数据，从数据库加载并写入 Redis
     * 3. 加载压缩摘要作为上下文
     * 4. 根据消息角色（SYSTEM/USER/ASSISTANT/TOOL）转换为对应的 Message 对象
     * 5. SystemMessage 插入到列表开头，其他消息按顺序追加
     *
     * @param chatSessionId 聊天会话 ID
     * @return 恢复后的消息列表，用于初始化 Agent 的记忆
     */
    private List<Message> loadMemory(String chatSessionId) {
        Integer configLength = agentConfig.getChatOptions().getMessageLength();
        int messageLength = configLength != null ? configLength : 6;

        List<ChatMessageDTO> chatMessages;

        // 1. 优先从 Redis 加载
        if (redisChatMemoryService.hasSession(chatSessionId)) {
            chatMessages = redisChatMemoryService.getRecentMessages(chatSessionId, messageLength);
            log.info("Loaded {} messages from Redis for session: {}", chatMessages.size(), chatSessionId);
        } else {
            // 2. Redis 没有数据，从数据库加载
            chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
            log.info("Loaded {} messages from database for session: {}", chatMessages.size(), chatSessionId);

            // 3. 写入 Redis 缓存
            if (!chatMessages.isEmpty()) {
                redisChatMemoryService.addMessages(chatSessionId, chatMessages);
                log.info("Cached {} messages to Redis for session: {}", chatMessages.size(), chatSessionId);
            }
        }

        // 存储转换后的 Spring AI Message 对象
        List<Message> memory = new ArrayList<>();

        // 4. 加载压缩摘要作为上下文
        String summary = chatMemoryCompressionService.getLatestSummary(chatSessionId);
        if (summary != null && !summary.isEmpty()) {
            memory.add(new SystemMessage("【历史对话摘要】\n" + summary));
            log.info("Loaded compression summary for session: {}", chatSessionId);
        }

        // 5. 遍历每条消息，根据角色类型进行转换
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    // 系统提示词，跳过空内容
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    // SystemMessage 必须放在最前面，所以插入到索引 0
                    memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    break;

                case USER:
                    // 用户消息，跳过空内容
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    break;

                case ASSISTANT:
                    // AI 助手消息，可能包含工具调用信息
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata().getToolCalls())
                            .build());
                    break;

                case TOOL:
                    // 工具响应消息，包含工具执行结果
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO.getMetadata().getToolResponse()))
                            .build());
                    break;

                default:
                    // 不支持的消息类型，记录错误并抛出异常
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        return memory;
    }

    /**
     * 将 Agent 实体对象转换为 DTO 配置对象
     *
     * 主要工作：
     * - 解析 JSON 字段（allowedTools、allowedKbs、chatOptions）
     * - 将字符串列表、Map 等复杂结构反序列化为 Java 对象
     *
     * @param agent Agent 实体对象
     * @return AgentDTO 配置对象
     * @throws IllegalStateException 如果 JSON 解析失败
     */
    private AgentDTO toAgentConfig(Agent agent) {
        try {
            agentConfig = agentConverter.toDTO(agent);
            return agentConfig;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    /**
     * 解析 Agent 允许访问的知识库列表
     *
     * 工作流程：
     * 1. 从 Agent 配置中获取允许的知识库 ID 列表
     * 2. 批量查询这些知识库的完整信息
     * 3. 转换为 DTO 对象返回
     *
     * 用途：
     * - 在 think() 阶段将知识库信息传递给 AI
     * - 限制 Agent 只能访问配置的知识库（权限控制）
     *
     * @param agentConfig Agent 配置对象
     * @return 知识库 DTO 列表
     */
    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        // 获取允许的知识库 ID 列表
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询知识库信息
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }

        // 转换为 DTO 对象
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    /**
     * 解析 Agent 允许使用的工具列表
     *
     * 工具分为两类：
     * 1. 固定工具：所有 Agent 都必须有的基础工具（如 TerminateTool）
     * 2. 可选工具：根据 Agent 配置动态添加的工具（如 DataBaseTools）
     *
     * 工作流程：
     * 1. 添加所有固定工具
     * 2. 根据 allowedTools 配置查找对应的可选工具
     * 3. 返回完整的工具列表
     *
     * @param agentConfig Agent 配置对象
     * @return 工具列表（固定工具 + 可选工具）
     */
    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        // ① 添加固定工具（系统强制，所有 Agent 都有）
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        // ② 获取允许使用的可选工具名称列表
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        // ③ 构建可选工具的名称 -> 工具对象映射
        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        // ④ 根据配置名称查找并添加工具
        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    /**
     * 将自定义 Tool 对象转换为 Spring AI 的 ToolCallback
     *
     * 为什么需要转换？
     * - Spring AI 的 ChatClient 只识别 ToolCallback 接口
     * - 我们的工具实现的是自定义 Tool 接口
     * - 需要通过 MethodToolCallbackProvider 进行适配
     *
     * 工作流程：
     * 1. 处理 AOP 代理（如果工具被 Spring 代理，获取真实对象）
     * 2. 使用 MethodToolCallbackProvider 将工具方法转换为 ToolCallback
     * 3. 收集所有工具的回调
     *
     * @param runtimeTools 工具列表
     * @return ToolCallback 列表，可直接传递给 ChatClient
     */
    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            // 解析工具的目标对象（处理 AOP 代理）
            Object target = resolveToolTarget(tool);

            // 将工具对象转换为 Spring AI 的 ToolCallback
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();

            // 添加到回调列表
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    /**
     * 解析工具的目标对象，处理 Spring AOP 代理
     *
     * 为什么需要这个方法？
     * - Spring 的 @Component 可能会创建代理对象
     * - MethodToolCallbackProvider 需要反射获取方法上的 @Tool 注解
     * - 如果是代理对象，反射可能拿不到注解，所以要获取原始对象
     *
     * @param tool 工具对象（可能是代理对象）
     * @return 真实的目标对象
     */
    private Object resolveToolTarget(Tool tool) {
        try {
            // 判断是否是 AOP 代理对象
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)  // 如果是代理，获取目标类
                    : tool;                           // 否则直接返回
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    /**
     * 构建 JChatMind Agent 运行时实例
     *
     * 这是工厂方法的最后一步，将所有准备好的参数传递给 JChatMind 构造函数
     *
     * @param agent Agent 实体对象
     * @param memory 恢复的历史对话记忆
     * @param knowledgeBases 允许访问的知识库列表
     * @param toolCallbacks 工具回调列表
     * @param chatSessionId 聊天会话 ID
     * @param userId 用户 ID（可选，用于获取用户的自定义模型配置）
     * @return 配置完成的 JChatMind 实例
     * @throws IllegalStateException 如果找不到对应的 ChatClient
     */
    private JChatMind buildAgentRuntime(
            Agent agent,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId,
            String userId
    ) {
        // ① 获取 ChatClient（支持用户级别配置）
        ChatClient chatClient;
        if (userId != null) {
            chatClient = userChatClientService.getChatClient(userId, agent.getModel());
        } else {
            chatClient = chatClientRegistry.get(agent.getModel());
        }
        
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }

        // ② 创建 JChatMind 实例，传入所有必需的配置
        return new JChatMind(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                null,
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                ragService,
                redisChatMemoryService,
                chatMemoryCompressionService
        );
    }

    /**
     * 创建 JChatMind Agent 实例（工厂方法的入口）
     *
     * 完整流程：
     * 1. 从数据库加载 Agent 配置
     * 2. 转换为 DTO 并解析 JSON 配置
     * 3. 恢复历史对话记忆
     * 4. 解析允许的知识库
     * 5. 解析允许的工具
     * 6. 构建 ToolCallback
     * 7. 创建并返回 JChatMind 实例
     *
     * 使用示例：
     * <pre>{@code
     * JChatMind agent = factory.create("agent-uuid-123", "session-uuid-456");
     * agent.run();
     * }</pre>
     *
     * @param agentId Agent 的唯一标识
     * @param chatSessionId 聊天会话的唯一标识
     * @return 配置完成、可立即运行的 JChatMind 实例
     */
    public JChatMind create(String agentId, String chatSessionId) {
        return create(agentId, chatSessionId, null);
    }

    /**
     * 创建 JChatMind Agent 实例（支持用户级别配置）
     *
     * @param agentId Agent 的唯一标识
     * @param chatSessionId 聊天会话的唯一标识
     * @param userId 用户 ID（可选，用于获取用户的自定义模型配置）
     * @return 配置完成、可立即运行的 JChatMind 实例
     */
    public JChatMind create(String agentId, String chatSessionId, String userId) {
        // 步骤1: 从数据库加载 Agent 配置
        Agent agent = loadAgent(agentId);

        // 步骤2: 转换为 DTO 配置（解析 JSON 字段）
        AgentDTO agentConfig = toAgentConfig(agent);

        // 步骤3: 恢复历史对话记忆
        List<Message> memory = loadMemory(chatSessionId);

        // 步骤4: 解析 Agent 支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);

        // 步骤5: 解析 Agent 支持的工具
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);

        // 步骤6: 将工具转换为 ToolCallback
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);

        // 步骤7: 创建 JChatMind 实例
        return buildAgentRuntime(
                agent,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId,
                userId
        );
    }
}
