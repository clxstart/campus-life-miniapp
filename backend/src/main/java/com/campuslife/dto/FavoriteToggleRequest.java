package com.campuslife.dto;
import jakarta.validation.constraints.*; import lombok.Data;
@Data public class FavoriteToggleRequest { @NotNull Long postId; }
