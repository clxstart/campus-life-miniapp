package com.campuslife.dto;
import jakarta.validation.constraints.*; import lombok.Data;
@Data public class CreatePostRequest { @NotNull Long userId; @NotBlank @Size(max=64) String title; @NotBlank String content; @Size(max=255) String image; @Size(max=16) String tag; String learnCategory; Long schoolId; @Size(max=64) String location; Long circleId; /* true=跨校帖（school_id 置空，所有学校可见）；缺省归本校 */ Boolean crossSchool; }
