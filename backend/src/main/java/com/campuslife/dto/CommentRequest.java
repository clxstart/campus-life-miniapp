package com.campuslife.dto; import jakarta.validation.constraints.*; import lombok.Data; @Data public class CommentRequest { @NotNull Long userId; @NotBlank @Size(max=255) String content; }
