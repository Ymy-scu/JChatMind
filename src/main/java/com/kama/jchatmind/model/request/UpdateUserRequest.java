package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class UpdateUserRequest {
    private String nickname;
    private String avatarUrl;
}
