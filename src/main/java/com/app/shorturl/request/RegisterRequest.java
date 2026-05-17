package com.app.shorturl.request;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Setter
@Getter
@Builder
public class RegisterRequest {
    private String fullName;
    private String username;
    private String email;
    private String password;
    private String confirmPassword;


}
