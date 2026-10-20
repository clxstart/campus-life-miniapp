package com.campuslife.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal;
@Data @TableName("shop_goods") public class ShopGoods {
 @TableId(type=IdType.AUTO) private Long id; private Long shopId; private String category; private String name; private BigDecimal price; private Integer monthlySales; private String image; private Integer sortNum; private Integer status;
}
