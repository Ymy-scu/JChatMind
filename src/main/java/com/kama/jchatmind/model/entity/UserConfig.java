package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserConfig {
    private String id;
    private String userId;
    private String provider;
    private String apiKey;
    private String baseUrl;
    private String modelName;
    private Boolean isDefault;
    private String config;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
