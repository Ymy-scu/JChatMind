package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserConfigMapper;
import com.kama.jchatmind.mapper.UserMapper;
import com.kama.jchatmind.model.entity.User;
import com.kama.jchatmind.model.entity.UserConfig;
import com.kama.jchatmind.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserConfigMapper userConfigMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, UserConfigMapper userConfigMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userConfigMapper = userConfigMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public User register(String username, String email, String password) {
        if (userMapper.selectByUsername(username) != null) {
            throw new BizException("用户名已存在");
        }
        if (userMapper.selectByEmail(email) != null) {
            throw new BizException("邮箱已被注册");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .nickname(username)
                .status(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = userMapper.insert(user);
        if (result <= 0) {
            throw new BizException("注册失败");
        }

        log.info("用户注册成功: username={}, email={}", username, email);
        return user;
    }

    @Override
    public User login(String usernameOrEmail, String password) {
        User user = userMapper.selectByUsername(usernameOrEmail);
        if (user == null) {
            user = userMapper.selectByEmail(usernameOrEmail);
        }
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (user.getStatus() != 1) {
            throw new BizException("账号已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException("密码错误");
        }

        log.info("用户登录成功: username={}", user.getUsername());
        return user;
    }

    @Override
    public User getUserById(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return user;
    }

    @Override
    @Transactional
    public User updateUser(String userId, String nickname, String avatarUrl) {
        User user = getUserById(userId);
        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
        }
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return user;
    }

    @Override
    @Transactional
    public void changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException("旧密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("用户密码修改成功: userId={}", userId);
    }

    @Override
    public List<UserConfig> getUserConfigs(String userId) {
        return userConfigMapper.selectByUserId(userId);
    }

    @Override
    public UserConfig getDefaultConfig(String userId) {
        return userConfigMapper.selectDefaultByUserId(userId);
    }

    @Override
    @Transactional
    public UserConfig addUserConfig(String userId, String provider, String apiKey, String baseUrl, String modelName, boolean isDefault, String config) {
        if (isDefault) {
            cancelDefaultConfig(userId);
        }

        UserConfig userConfig = UserConfig.builder()
                .userId(userId)
                .provider(provider)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .isDefault(isDefault)
                .config(config)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = userConfigMapper.insert(userConfig);
        if (result <= 0) {
            throw new BizException("添加配置失败");
        }

        log.info("用户配置添加成功: userId={}, provider={}, modelName={}", userId, provider, modelName);
        return userConfig;
    }

    @Override
    @Transactional
    public UserConfig updateUserConfig(String userId, String configId, String apiKey, String baseUrl, String modelName, Boolean isDefault, String config) {
        UserConfig existingConfig = userConfigMapper.selectById(configId);
        if (existingConfig == null || !existingConfig.getUserId().equals(userId)) {
            throw new BizException("配置不存在");
        }

        if (isDefault != null && isDefault) {
            cancelDefaultConfig(userId);
        }

        if (apiKey != null) existingConfig.setApiKey(apiKey);
        if (baseUrl != null) existingConfig.setBaseUrl(baseUrl);
        if (modelName != null) existingConfig.setModelName(modelName);
        if (isDefault != null) existingConfig.setIsDefault(isDefault);
        if (config != null) existingConfig.setConfig(config);
        existingConfig.setUpdatedAt(LocalDateTime.now());

        userConfigMapper.updateById(existingConfig);
        return existingConfig;
    }

    @Override
    @Transactional
    public void deleteUserConfig(String userId, String configId) {
        UserConfig existingConfig = userConfigMapper.selectById(configId);
        if (existingConfig == null || !existingConfig.getUserId().equals(userId)) {
            throw new BizException("配置不存在");
        }
        userConfigMapper.deleteById(configId);
        log.info("用户配置删除成功: userId={}, configId={}", userId, configId);
    }

    private void cancelDefaultConfig(String userId) {
        UserConfig currentDefault = userConfigMapper.selectDefaultByUserId(userId);
        if (currentDefault != null) {
            currentDefault.setIsDefault(false);
            currentDefault.setUpdatedAt(LocalDateTime.now());
            userConfigMapper.updateById(currentDefault);
        }
    }
}
