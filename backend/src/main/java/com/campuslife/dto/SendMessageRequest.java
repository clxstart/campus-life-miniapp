package com.campuslife.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data public class SendMessageRequest {
 @NotNull @Min(1) private Long userId;
 @NotBlank @Size(max=1000,message="消息不能超过1000字") private String content;
 @NotBlank @Size(max=64) private String clientId;
}
