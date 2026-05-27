package com.kama.jchatmind.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddUserConfigRequest {
    @NotBlank(message = "模型提供商不能为空")
    private String provider;

    @NotBlank(message = "API Key 不能为空")
    private String apiKey;

    private String baseUrl;

    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    private Boolean isDefault;

    private String config;
}
