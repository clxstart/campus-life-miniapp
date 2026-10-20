package com.campuslife.dto;
import lombok.AllArgsConstructor; import lombok.Data;
@Data @AllArgsConstructor public class ApiResponse<T>{ private int code; private String msg; private T data; public static <T> ApiResponse<T> ok(T d){return new ApiResponse<>(0,"ok",d);} }
