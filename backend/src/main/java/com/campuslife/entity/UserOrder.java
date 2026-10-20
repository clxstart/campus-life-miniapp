package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal; import java.time.LocalDateTime;
@Data @TableName("user_order") public class UserOrder {
 @TableId(type=IdType.AUTO) private Long id; private Long userId; private Long shopId; private String items; private String address; private String remark; private BigDecimal subtotal; private BigDecimal packingFee; private BigDecimal deliveryFee; private BigDecimal discountAmount; private BigDecimal total; private String status; private LocalDateTime createdAt;
}
