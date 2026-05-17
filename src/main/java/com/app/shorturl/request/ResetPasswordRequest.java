package com.app.shorturl.request;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Setter
@Getter
@Builder
public class ResetPasswordRequest {
    private String token;
    private String password;
    private String confirmPassword;


}
