package com.campuslife.dto;
import jakarta.validation.constraints.*; import lombok.Data;
@Data public class IdolJoinRequest {
    @NotNull Long userId;
    @NotBlank @Size(max=32) String name;
    @NotNull @Min(1) @Max(2) Integer gender;
    @Size(max=64) String college;
    @NotBlank @Size(max=255) String photo;
    @Size(max=128) String slogan;
}
