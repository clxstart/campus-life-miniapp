package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*; import com.fasterxml.jackson.annotation.JsonIgnore; import lombok.Data; import java.math.BigDecimal;
@Data @TableName("shop") public class Shop {
 @TableId(type=IdType.AUTO) private Long id; private String name; private String cover; private String category; private BigDecimal rating; private Integer monthlySales; private BigDecimal minPrice; private BigDecimal deliveryFee; private Integer deliveryTime; private String discount; private String notice;
 // 多学校：店铺归属学校（各校商家不同）
 private Long schoolId;
 @JsonIgnore private BigDecimal discountThreshold; @JsonIgnore private BigDecimal discountAmount; @JsonIgnore private Integer status;
}
