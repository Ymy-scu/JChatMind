package com.kama.jchatmind.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询扩写 Advisor
 * 在用户提问时自动进行查询扩写，将扩写后的查询添加到 system prompt 中
 * 输入：查询重写后的用户消息（由 QueryRewriteAdvisor 处理）
 */
@Slf4j
public class QueryExpansionAdvisor implements BaseAdvisor {

    private final ChatModel chatModel;
    private final int order;

    public QueryExpansionAdvisor(ChatModel chatModel) {
        this(chatModel, 0);
    }

    public QueryExpansionAdvisor(ChatModel chatModel, int order) {
        this.chatModel = chatModel;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        // 获取用户消息（此时应该是 QueryRewriteAdvisor 处理后的结果）
        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions();

        // 获取最后一条用户消息
        String userMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                userMessage = messages.get(i).getText();
                break;
            }
        }

        if (userMessage == null || userMessage.trim().isEmpty()) {
            return request;
        }

        try {
            // 基于重写后的查询进行扩写
            List<String> expandedQueries = expandQuery(userMessage);

            if (expandedQueries.size() <= 1) {
                return request;
            }

            // 构建扩写提示信息
            String expandedText = expandedQueries.stream()
                    .skip(1)
                    .collect(Collectors.joining("\n- ", "- ", ""));

            String expansionHint = """
                    
                    [系统提示] 用户的问题已扩展为以下相关查询，用于更全面地理解用户意图：
                    %s
                    请综合考虑这些查询来回答用户的问题。
                    """.formatted(expandedText);

            log.info("查询扩写: 输入='{}', 扩写数量={}", userMessage, expandedQueries.size() - 1);

            // 将扩写结果添加到消息列表中
            List<Message> newMessages = new ArrayList<>(messages);
            newMessages.add(new SystemMessage(expansionHint));

            Prompt newPrompt = new Prompt(newMessages);
            return ChatClientRequest.builder()
                    .prompt(newPrompt)
                    .context(request.context())
                    .build();

        } catch (Exception e) {
            log.warn("查询扩写失败，使用原始查询: {}", e.getMessage());
            return request;
        }
    }

    private List<String> expandQuery(String originalQuery) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            return List.of(originalQuery);
        }

        try {
            String prompt = """
                    你是一个查询扩写专家。请对用户的查询进行扩写和改写，生成多个语义相关的查询，用于提高知识库检索的召回率。
                    
                    要求：
                    1. 保留原始查询的核心语义
                    2. 从不同角度改写（如同义词替换、更正式的表达、更详细的描述）
                    3. 生成 2-3 个扩写查询
                    4. 每个查询占一行
                    5. 不要添加序号或其他标记，只返回查询文本
                    6. 不要解释，直接返回查询
                    
                    示例输入：如何学习 Java 编程？
                    示例输出：
                    Java 编程入门教程
                    学习 Java 语言的方法和资源
                    Java 编程学习路径和建议
                    """;

            ChatClient chatClient = ChatClient.create(chatModel);
            String response = chatClient.prompt()
                    .system(prompt)
                    .user(originalQuery)
                    .call()
                    .content();

            List<String> expandedQueries = new ArrayList<>();
            expandedQueries.add(originalQuery);

            if (response != null && !response.trim().isEmpty()) {
                String[] queries = response.trim().split("\n");
                for (String query : queries) {
                    String trimmed = query.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals(originalQuery)) {
                        expandedQueries.add(trimmed);
                    }
                }
            }

            log.info("查询扩写结果: 输入='{}', 扩写={}", originalQuery, expandedQueries);
            return expandedQueries;

        } catch (Exception e) {
            log.error("查询扩写失败，使用原始查询", e);
            return List.of(originalQuery);
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest processedRequest = before(request, chain);
        ChatClientResponse response = chain.nextCall(processedRequest);
        return after(response, chain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest processedRequest = before(request, chain);
        return chain.nextStream(processedRequest)
                .map(response -> after(response, chain))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getName() {
        return "QueryExpansionAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }
}
