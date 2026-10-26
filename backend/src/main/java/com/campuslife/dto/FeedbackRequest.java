package com.campuslife.dto;
import jakarta.validation.constraints.*; import lombok.Data;
@Data public class FeedbackRequest { @NotNull Long userId; @NotBlank @Size(max=255) String content; }
