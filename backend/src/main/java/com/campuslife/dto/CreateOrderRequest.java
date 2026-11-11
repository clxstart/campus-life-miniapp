package com.campuslife.dto;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import lombok.Data; import java.util.List;
@Data public class CreateOrderRequest {
 @NotNull(message="用户不能为空") @Positive(message="用户无效") private Long userId;
 @NotNull(message="店铺不能为空") @Positive(message="店铺无效") private Long shopId;
 @NotNull(message="请选择商品") @Size(min=1,max=50,message="请选择1至50种商品") @Valid private List<@NotNull(message="商品不能为空") Item> items;
 @NotBlank(message="请填写收货地址") @Size(max=128,message="地址不能超过128字") private String address;
 @Size(max=32,message="联系人姓名不能超过32字") private String contactName;
 @Size(max=20,message="联系电话不能超过20位") private String contactPhone;
 @Size(max=200,message="备注不能超过200字") private String remark;
 /** 客户端下单幂等键（同一次结算重复提交/重启恢复时保持不变）；可选，兼容旧客户端 */
 @Pattern(regexp="^[A-Za-z0-9_-]{8,64}$",message="幂等键格式无效") private String idempotencyKey;
 /** V3.1 券：可选。抵扣额由服务端按券面额与门槛计算，客户端不能传金额。 */
 private Long couponId;
 @Data public static class Item { @NotNull(message="商品不能为空") @Positive(message="商品无效") private Long goodsId; @NotNull(message="数量不能为空") @Min(value=1,message="数量至少为1") @Max(value=99,message="单件商品最多购买99份") private Integer count; }
}
