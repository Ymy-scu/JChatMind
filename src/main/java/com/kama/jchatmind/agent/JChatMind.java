package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.SseService;
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

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {

    private String agentId;
    private String name;
    private String description;
    private String systemPrompt;
    private ChatClient chatClient;
    private AgentState agentState;
    private List<ToolCallback> availableTools;
    private List<KnowledgeBaseDTO> availableKbs;
    private ToolCallingManager toolCallingManager;
    private ChatMemory chatMemory;
    private String chatSessionId;
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 100;
    private ChatOptions chatOptions;
    private SseService sseService;
    private ChatMessageConverter chatMessageConverter;
    private ChatMessageFacadeService chatMessageFacadeService;
    private ChatResponse lastChatResponse;
    private RagService ragService;
    private static final double SIMILARITY_THRESHOLD = 0.7;

    public JChatMind() {
    }

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
                     RagService ragService
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
        this.agentState = AgentState.IDLE;

        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    private void ensureKnowledgeContext() {
        if (availableKbs == null || availableKbs.isEmpty() || ragService == null) {
            return;
        }

        List<Message> messages = chatMemory.get(chatSessionId);
        String currentQuery = extractLastUserMessage(messages);
        if (!StringUtils.hasText(currentQuery)) {
            return;
        }

        List<ToolResponseMessage> historyResults = messages.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .filter(m -> m.getResponses().stream()
                        .anyMatch(r -> r.name().equals("KnowledgeTool")))
                .toList();

        if (!historyResults.isEmpty()) {
            float[] currentEmbedding;
            try {
                currentEmbedding = ragService.embed(currentQuery);
            } catch (Exception e) {
                log.warn("Failed to embed current query for similarity check", e);
                return;
            }

            boolean relevantFound = historyResults.stream().anyMatch(result -> {
                String historyContent = extractKnowledgeContent(result);
                if (!StringUtils.hasText(historyContent)) {
                    return false;
                }
                try {
                    float[] historyEmbedding = ragService.embed(historyContent);
                    double similarity = cosineSimilarity(currentEmbedding, historyEmbedding);
                    return similarity > SIMILARITY_THRESHOLD;
                } catch (Exception e) {
                    log.warn("Failed to compute similarity with history result", e);
                    return false;
                }
            });

            if (relevantFound) {
                log.info("Found relevant knowledge from history, skipping RAG retrieval");
                return;
            }
        }

        for (KnowledgeBaseDTO kb : availableKbs) {
            try {
                List<String> results = ragService.similaritySearch(kb.getId(), currentQuery);
                if (results != null && !results.isEmpty()) {
                    String resultContent = String.join("\n", results);
                    String knowledgeContext = "【知识库检索结果】\n" + resultContent;
                    chatMemory.add(chatSessionId, new SystemMessage(knowledgeContext));
                    log.info("Pre-retrieved knowledge for kb: {}, results count: {}", kb.getId(), results.size());
                }
            } catch (Exception e) {
                log.warn("Failed to pre-retrieve knowledge from kb: {}", kb.getId(), e);
            }
        }
    }

    private String extractLastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage) {
                return ((UserMessage) msg).getText();
            }
        }
        return null;
    }

    private String extractKnowledgeContent(ToolResponseMessage toolResponse) {
        return toolResponse.getResponses().stream()
                .filter(r -> r.name().equals("KnowledgeTool"))
                .map(r -> r.responseData())
                .collect(Collectors.joining("\n"));
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
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
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    private void persistAndSend(ChatMessageDTO chatMessageDTO) {
        CreateChatMessageResponse saved = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(saved.getChatMessageId());

        ChatMessageVO vo = chatMessageConverter.toVO(chatMessageDTO);
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(vo)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(saved.getChatMessageId())
                        .build())
                .build();
        sseService.send(this.chatSessionId, sseMessage);
    }

    private void sendContentDelta(String text) {
        SseMessage delta = SseMessage.builder()
                .type(SseMessage.Type.AI_CONTENT_DELTA)
                .payload(SseMessage.Payload.builder()
                        .contentDelta(text)
                        .build())
                .build();
        sseService.send(this.chatSessionId, delta);
    }

    private boolean think() {
        ensureKnowledgeContext();

        String thinkPrompt = """
                你可以调用工具获取信息。拿到工具返回结果后，必须用自然语言总结给用户。
                可用的知识库：%s
                当用户的问题与知识库相关时，你必须先调用 KnowledgeTool 工具在知识库中进行检索，然后再回答问题。
                如果提供了知识库ID，请优先使用 KnowledgeTool 进行检索。
                """.formatted(this.availableKbs);

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        final StringBuilder fullContent = new StringBuilder();

        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String text = response.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        fullContent.append(text);
                        sendContentDelta(text);
                    }
                })
                .blockLast();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(fullContent.toString())
                .sessionId(this.chatSessionId)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(toolCalls)
                        .build())
                .build();
        persistAndSend(chatMessageDTO);

        this.chatMemory.add(this.chatSessionId, AssistantMessage.builder()
                .content(fullContent.toString())
                .toolCalls(toolCalls)
                .build());

        logToolCalls(toolCalls);

        return !toolCalls.isEmpty();
    }

    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        this.chatMemory.add(this.chatSessionId, toolResponseMessage);

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

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    private void step() {
        if (think()) {
            execute();
        } else {
            agentState = AgentState.FINISHED;
        }
    }

    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                int currentStep = i + 1;
                step();
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        } finally {
            SseMessage doneMsg = SseMessage.builder()
                    .type(SseMessage.Type.AI_DONE)
                    .payload(SseMessage.Payload.builder().build())
                    .build();
            sseService.send(this.chatSessionId, doneMsg);
        }
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
