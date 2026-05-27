package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class UpdateUserConfigRequest {
    private String apiKey;
    private String baseUrl;
    private String modelName;
    private Boolean isDefault;
    private String config;
}
