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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 查询重写 Advisor
 * 在用户提问时自动进行查询重写优化，使用户意图更清晰，提高 AI 理解准确度
 */
@Slf4j
public class QueryRewriteAdvisor implements BaseAdvisor {

    private final ChatModel chatModel;
    private final int order;

    private static final String REWRITE_SYSTEM_PROMPT = """
            你是一个查询重写优化专家。你的任务是将用户的原始问题重写为更清晰、更具体、更易于AI理解的形式。

            重写规则：
            1. 保留用户原始问题的核心意图和所有关键信息
            2. 补充必要的上下文，使问题更完整
            3. 纠正口语化表达、错别字、语法错误
            4. 将模糊的问题具体化（如"那个东西"→ 根据上下文推断具体指什么）
            5. 如果用户的问题已经很清晰，可以直接返回原文
            6. 只返回重写后的查询，不要解释、不要添加额外内容

            示例：
            用户: 帮我看看那个bug
            重写: 请帮我分析代码中可能存在的bug问题

            用户: 这个怎么用啊
            重写: 请详细说明这个功能/工具的使用方法

            用户: Java怎么学
            重写: 请介绍学习Java编程语言的推荐方法和学习路径

            用户: 帮我写个函数
            重写: 请帮我编写一个函数（请补充具体的功能需求和编程语言）
            """;

    public QueryRewriteAdvisor(ChatModel chatModel) {
        this(chatModel, -1);
    }

    public QueryRewriteAdvisor(ChatModel chatModel, int order) {
        this.chatModel = chatModel;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        // 获取原始消息列表
        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions();

        // 找到最后一条用户消息
        String userMessage = null;
        int userMessageIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                userMessage = messages.get(i).getText();
                userMessageIndex = i;
                break;
            }
        }

        if (userMessage == null || userMessage.trim().isEmpty()) {
            return request;
        }

        // 跳过过短的消息（不需要重写）
        if (userMessage.trim().length() <= 5) {
            return request;
        }

        try {
            String rewrittenQuery = rewriteQuery(userMessage);
            log.info("查询重写结果: 原始='{}', 重写='{}'", userMessage, rewrittenQuery);
            if (rewrittenQuery == null || rewrittenQuery.trim().isEmpty() || rewrittenQuery.equals(userMessage)) {
                log.debug("查询重写结果与原始查询相同，跳过替换");
                return request;
            }

            log.info("查询重写: 原始='{}', 重写='{}'", userMessage, rewrittenQuery);

            // 构建新的消息列表，替换最后一条用户消息
            List<Message> newMessages = new java.util.ArrayList<>(messages);
            newMessages.set(userMessageIndex, new UserMessage(rewrittenQuery));

            // 构建新的请求
            Prompt newPrompt = new Prompt(newMessages);
            return ChatClientRequest.builder()
                    .prompt(newPrompt)
                    .context(request.context())
                    .build();

        } catch (Exception e) {
            log.warn("查询重写失败，使用原始查询: {}", e.getMessage());
            return request;
        }
    }

    /**
     * 调用 LLM 进行查询重写
     */
    private String rewriteQuery(String originalQuery) {
        ChatClient chatClient = ChatClient.create(chatModel);
        return chatClient.prompt()
                .system(REWRITE_SYSTEM_PROMPT)
                .user(originalQuery)
                .call()
                .content();
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
        return "QueryRewriteAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }
}
