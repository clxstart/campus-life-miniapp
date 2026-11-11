package com.campuslife.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 履约账单写入。**不是钱包**：这里只记"应付/应收多少"，不产生可提现余额。
 * biz_no 唯一键保证重复结算只记一次。
 */
@Mapper
public interface EarningsLedgerMapper {

    @Insert("""
        INSERT IGNORE INTO earnings_ledger(biz_no, biz_type, ref_id, user_id, entry_type, amount_cents, status, note, operator_id)
        VALUES(#{bizNo}, #{bizType}, #{refId}, #{userId}, #{entryType}, #{amountCents}, #{status}, #{note}, #{operatorId})
        """)
    int record(@Param("bizNo") String bizNo, @Param("bizType") String bizType,
               @Param("refId") Long refId, @Param("userId") Long userId,
               @Param("entryType") String entryType, @Param("amountCents") int amountCents,
               @Param("status") String status, @Param("note") String note,
               @Param("operatorId") Long operatorId);

    /** INSERT IGNORE 返回 0 时用来区分“已存在（幂等）”和“被静默吞掉的写入失败”。 */
    @Select("SELECT COUNT(*) FROM earnings_ledger WHERE biz_no = #{bizNo}")
    int countByBizNo(@Param("bizNo") String bizNo);

    /** 退款时把平台补贴条目置 void；条件 status<>'void' 保证重复退款不会反复写。 */
    @Update("UPDATE earnings_ledger SET status = 'void' WHERE biz_no = #{bizNo} AND status <> 'void'")
    int voidByBizNo(@Param("bizNo") String bizNo);

    /**
     * 状态推进（frozen → settled / refunded）。
     * 带 from 条件是为了幂等：同一笔业务重复推进只会命中一次，第二次影响 0 行。
     */
    @Update("UPDATE earnings_ledger SET status = #{to} WHERE biz_no = #{bizNo} AND status = #{from}")
    int switchStatus(@Param("bizNo") String bizNo, @Param("from") String from, @Param("to") String to);

    /**
     * 争议裁定后按「管理员确认的计价数量」重算金额。
     *
     * 为什么需要它：争议冻结时 {@link #record} 已经按**承接方申报量**写入了三行（INSERT IGNORE），
     * 之后裁定 settled 若直接把 frozen 切成 settled，金额会永远停在申报值 —— 平台按申报量收服务费、
     * 雇主按申报量扣款、承接方按申报量拿钱，与管理员确认的数量无关。这是资金口径错误，不只是展示问题。
     * 幂等：写入的是同一个确定值，重复调用结果一致。
     */
    @Update("UPDATE earnings_ledger SET amount_cents = #{amountCents}, note = #{note} WHERE biz_no = #{bizNo}")
    int reprice(@Param("bizNo") String bizNo, @Param("amountCents") int amountCents, @Param("note") String note);

    /** 某张履约单的账单（只读，给详情页展示）。 */
    @Select("""
        SELECT id, biz_no AS bizNo, biz_type AS bizType, ref_id AS refId, user_id AS userId,
               entry_type AS entryType, amount_cents AS amountCents, status, note,
               operator_id AS operatorId, created_at AS createdAt
        FROM earnings_ledger
        WHERE biz_type = 'job' AND ref_id = #{contractId}
        ORDER BY id
        """)
    List<Map<String, Object>> byRef(@Param("contractId") Long contractId);
}
