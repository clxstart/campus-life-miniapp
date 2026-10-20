package com.campuslife.dto;
import jakarta.validation.constraints.*; import lombok.Data;
@Data public class AddressAddRequest { @Size(max=32) String contact; @Size(max=20) String phone; @NotBlank @Size(max=128) String detail; }
