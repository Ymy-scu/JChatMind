package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.User;
import com.kama.jchatmind.model.entity.UserConfig;

import java.util.List;

public interface UserService {

    User register(String username, String email, String password);

    User login(String usernameOrEmail, String password);

    User getUserById(String userId);

    User updateUser(String userId, String nickname, String avatarUrl);

    void changePassword(String userId, String oldPassword, String newPassword);

    List<UserConfig> getUserConfigs(String userId);

    UserConfig getDefaultConfig(String userId);

    UserConfig addUserConfig(String userId, String provider, String apiKey, String baseUrl, String modelName, boolean isDefault, String config);

    UserConfig updateUserConfig(String userId, String configId, String apiKey, String baseUrl, String modelName, Boolean isDefault, String config);

    void deleteUserConfig(String userId, String configId);
}
