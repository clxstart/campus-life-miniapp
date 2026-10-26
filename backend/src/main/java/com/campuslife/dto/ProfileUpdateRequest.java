package com.campuslife.dto;
import jakarta.validation.constraints.*; import lombok.Data;
@Data public class ProfileUpdateRequest { @NotBlank @Size(max=32) String nickname; }
