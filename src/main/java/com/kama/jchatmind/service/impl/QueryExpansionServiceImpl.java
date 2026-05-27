package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.service.QueryExpansionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class QueryExpansionServiceImpl implements QueryExpansionService {

    private final ChatClientRegistry chatClientRegistry;

    public QueryExpansionServiceImpl(ChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    @Override
    public List<String> expandQuery(String originalQuery) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            return List.of(originalQuery);
        }

        try {
            ChatClient chatClient = chatClientRegistry.get("deepseek-chat");
            if (chatClient == null) {
                log.warn("未找到 deepseek-chat 模型，跳过查询扩写");
                return List.of(originalQuery);
            }

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

            log.info("查询扩写结果: 原始='{}', 扩写={}", originalQuery, expandedQueries);
            return expandedQueries;

        } catch (Exception e) {
            log.error("查询扩写失败，使用原始查询", e);
            return List.of(originalQuery);
        }
    }
}
