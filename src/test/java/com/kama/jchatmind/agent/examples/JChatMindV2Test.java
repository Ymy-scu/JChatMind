package com.kama.jchatmind.agent.examples;

import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.test.CityTool;
import com.kama.jchatmind.agent.tools.test.DateTool;
import com.kama.jchatmind.agent.tools.test.WeatherTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JChatMindV2 测试类
 * 测试工具调用功能（ReAct 模型）
 */
@SpringBootTest
public class JChatMindV2Test {

    @Autowired
    @Qualifier("qwen-plus")
    private ChatClient chatClient;

    @Autowired
    private CityTool cityTool;

    @Autowired
    private DateTool dateTool;

    @Autowired
    private WeatherTool weatherTool;

    @Autowired
    private DataBaseTools dataBaseTools;

    @Test
    public void testToolCalling() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool,dataBaseTools)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息。",
                chatClient,
                20,
                "test-session-v2",
                Arrays.asList(toolCallbacks)
        );

        // 测试需要调用工具的对话
        String userInput = "今天的天气怎么样？";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testMultipleToolCalls() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息。",
                chatClient,
                20,
                "test-session-v2-multi",
                Arrays.asList(toolCallbacks)
        );

        // 测试需要调用多个工具的对话
        String userInput = "请告诉我今天的日期和当前城市，然后查询这个城市今天的天气。";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testConversationWithoutToolCalling() {
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();
        // 创建 V2 实例（不提供工具）
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个友好的助手。",
                chatClient,
                20,
                "test-session-v2-no-tool",
                Arrays.asList(toolCallbacks) // 不提供工具
        );

        // 测试不需要工具的普通对话
        String userInput = "你好，请介绍一下你自己。";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
    }

    @Test
    public void testReActLoop() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息。",
                chatClient,
                20,
                "test-session-v2-react",
                Arrays.asList(toolCallbacks)
        );

        // 测试 ReAct 循环（think-execute）
        String userInput = "我想知道今天的天气，请先告诉我今天的日期。";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testDataBaseTools() {
        // 准备工具回调，只使用 DataBaseTools
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool,dataBaseTools)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-db",
                "数据库查询助手",
                "你是一个智能数据库助手，可以帮助用户查询数据库中的信息。你可以使用 databaseQuery 工具执行 SQL 查询来获取数据。",
                chatClient,
                20,
                "test-session-db",
                Arrays.asList(toolCallbacks)
        );

        // 测试1：查询知识库列表
        System.out.println("\n========== 测试1：查询知识库列表 ==========");
        String userInput1 = "请帮我查询数据库中有哪些知识库？列出所有知识库的名称和描述。";
        String response1 = agent.chat(userInput1);

        assertNotNull(response1);
        assertTrue(response1.length() > 0);
        System.out.println("用户输入: " + userInput1);
        System.out.println("AI 回复: " + response1);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());

        // 测试2：查询Agent信息
        System.out.println("\n========== 测试2：查询Agent信息 ==========");
        String userInput2 = "请查询数据库中有多少个Agent，并列出它们的名称。";
        String response2 = agent.chat(userInput2);

        assertNotNull(response2);
        assertTrue(response2.length() > 0);
        System.out.println("用户输入: " + userInput2);
        System.out.println("AI 回复: " + response2);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());

        // 测试3：查询聊天会话
        System.out.println("\n========== 测试3：查询聊天会话统计 ==========");
        String userInput3 = "帮我统计一下数据库中有多少条聊天会话记录？";
        String response3 = agent.chat(userInput3);

        assertNotNull(response3);
        assertTrue(response3.length() > 0);
        System.out.println("用户输入: " + userInput3);
        System.out.println("AI 回复: " + response3);
    }
}


