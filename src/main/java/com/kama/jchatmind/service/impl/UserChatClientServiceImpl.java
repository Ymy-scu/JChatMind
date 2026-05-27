package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.model.entity.UserConfig;
import com.kama.jchatmind.service.UserChatClientService;
import com.kama.jchatmind.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class UserChatClientServiceImpl implements UserChatClientService {

    private final ChatClientRegistry chatClientRegistry;
    private final UserService userService;

    // 用户级别的 ChatClient 缓存: userId -> (modelKey -> ChatClient)
    private final Map<String, Map<String, ChatClient>> userClientCache = new ConcurrentHashMap<>();

    public UserChatClientServiceImpl(ChatClientRegistry chatClientRegistry, UserService userService) {
        this.chatClientRegistry = chatClientRegistry;
        this.userService = userService;
    }

    @Override
    public ChatClient getChatClient(String userId, String modelKey) {
        if (userId == null) {
            return chatClientRegistry.get(modelKey);
        }

        Map<String, ChatClient> userClients = userClientCache.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        ChatClient cachedClient = userClients.get(modelKey);

        if (cachedClient != null) {
            return cachedClient;
        }

        try {
            UserConfig userConfig = findUserConfigForModel(userId, modelKey);
            if (userConfig != null) {
                ChatClient client = createChatClientFromConfig(userConfig);
                userClients.put(modelKey, client);
                return client;
            }
        } catch (Exception e) {
            log.warn("创建用户自定义 ChatClient 失败，使用系统默认: userId={}, modelKey={}", userId, modelKey, e);
        }

        return chatClientRegistry.get(modelKey);
    }

    @Override
    public ChatClient getDefaultChatClient(String userId) {
        if (userId == null) {
            return chatClientRegistry.get("deepseek-chat");
        }

        UserConfig defaultConfig = userService.getDefaultConfig(userId);
        if (defaultConfig != null) {
            String modelKey = defaultConfig.getProvider() + "-" + defaultConfig.getModelName();
            return getChatClient(userId, modelKey);
        }

        return chatClientRegistry.get("deepseek-chat");
    }

    private UserConfig findUserConfigForModel(String userId, String modelKey) {
        String[] parts = modelKey.split("-", 2);
        if (parts.length < 2) {
            return null;
        }

        String provider = parts[0];
        String modelName = parts[1];

        return userService.getUserConfigs(userId).stream()
                .filter(config -> config.getProvider().equalsIgnoreCase(provider) 
                        && config.getModelName().equalsIgnoreCase(modelName))
                .findFirst()
                .orElse(null);
    }

    private ChatClient createChatClientFromConfig(UserConfig config) {
        String provider = config.getProvider().toLowerCase();
        String modelName = config.getModelName();
        String apiKey = config.getApiKey();
        String baseUrl = config.getBaseUrl();

        String resolvedBaseUrl = resolveBaseUrl(provider, baseUrl);

        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(resolvedBaseUrl)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();

        log.info("创建用户自定义 ChatClient: provider={}, model={}, baseUrl={}", provider, modelName, resolvedBaseUrl);
        return ChatClient.create(chatModel);
    }

    private String resolveBaseUrl(String provider, String customBaseUrl) {
        if (customBaseUrl != null && !customBaseUrl.isEmpty()) {
            return customBaseUrl;
        }

        return switch (provider) {
            case "deepseek" -> "https://api.deepseek.com";
            case "zhipuai" -> "https://open.bigmodel.cn/api/paas";
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode";
            case "openai" -> "https://api.openai.com";
            default -> "https://api.openai.com";
        };
    }

    public void clearUserCache(String userId) {
        userClientCache.remove(userId);
        log.info("清除用户 ChatClient 缓存: userId={}", userId);
    }
}
