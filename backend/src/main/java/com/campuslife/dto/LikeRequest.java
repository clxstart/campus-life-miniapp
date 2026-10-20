package com.campuslife.dto; import jakarta.validation.constraints.NotNull; import lombok.Data; @Data public class LikeRequest { @NotNull Long userId; }
