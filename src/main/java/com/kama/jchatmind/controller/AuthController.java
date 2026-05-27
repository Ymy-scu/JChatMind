package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.User;
import com.kama.jchatmind.model.entity.UserConfig;
import com.kama.jchatmind.model.request.*;
import com.kama.jchatmind.model.response.LoginResponse;
import com.kama.jchatmind.model.response.UserConfigResponse;
import com.kama.jchatmind.model.response.UserResponse;
import com.kama.jchatmind.service.UserService;
import com.kama.jchatmind.util.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request.getUsername(), request.getEmail(), request.getPassword());
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return ApiResponse.success(LoginResponse.builder()
                .token(token)
                .user(toUserResponse(user))
                .build());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.login(request.getUsernameOrEmail(), request.getPassword());
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return ApiResponse.success(LoginResponse.builder()
                .token(token)
                .user(toUserResponse(user))
                .build());
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> getCurrentUser(@RequestAttribute("userId") String userId) {
        User user = userService.getUserById(userId);
        return ApiResponse.success(toUserResponse(user));
    }

    @PutMapping("/me")
    public ApiResponse<UserResponse> updateCurrentUser(
            @RequestAttribute("userId") String userId,
            @RequestBody UpdateUserRequest request) {
        User user = userService.updateUser(userId, request.getNickname(), request.getAvatarUrl());
        return ApiResponse.success(toUserResponse(user));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success(null);
    }

    @GetMapping("/configs")
    public ApiResponse<List<UserConfigResponse>> getUserConfigs(@RequestAttribute("userId") String userId) {
        List<UserConfig> configs = userService.getUserConfigs(userId);
        List<UserConfigResponse> responses = configs.stream()
                .map(this::toUserConfigResponse)
                .collect(Collectors.toList());
        return ApiResponse.success(responses);
    }

    @PostMapping("/configs")
    public ApiResponse<UserConfigResponse> addUserConfig(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody AddUserConfigRequest request) {
        UserConfig config = userService.addUserConfig(
                userId,
                request.getProvider(),
                request.getApiKey(),
                request.getBaseUrl(),
                request.getModelName(),
                request.getIsDefault() != null ? request.getIsDefault() : false,
                request.getConfig()
        );
        return ApiResponse.success(toUserConfigResponse(config));
    }

    @PutMapping("/configs/{configId}")
    public ApiResponse<UserConfigResponse> updateUserConfig(
            @RequestAttribute("userId") String userId,
            @PathVariable String configId,
            @RequestBody UpdateUserConfigRequest request) {
        UserConfig config = userService.updateUserConfig(
                userId,
                configId,
                request.getApiKey(),
                request.getBaseUrl(),
                request.getModelName(),
                request.getIsDefault(),
                request.getConfig()
        );
        return ApiResponse.success(toUserConfigResponse(config));
    }

    @DeleteMapping("/configs/{configId}")
    public ApiResponse<Void> deleteUserConfig(
            @RequestAttribute("userId") String userId,
            @PathVariable String configId) {
        userService.deleteUserConfig(userId, configId);
        return ApiResponse.success(null);
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private UserConfigResponse toUserConfigResponse(UserConfig config) {
        return UserConfigResponse.builder()
                .id(config.getId())
                .provider(config.getProvider())
                .apiKey(maskApiKey(config.getApiKey()))
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .isDefault(config.getIsDefault())
                .config(config.getConfig())
                .createdAt(config.getCreatedAt())
                .build();
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
