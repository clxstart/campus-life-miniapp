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

    /**
     * 状态推进（frozen → settled / refunded）。
     * 带 from 条件是为了幂等：同一笔业务重复推进只会命中一次，第二次影响 0 行。
     */
    @Update("UPDATE earnings_ledger SET status = #{to} WHERE biz_no = #{bizNo} AND status = #{from}")
    int switchStatus(@Param("bizNo") String bizNo, @Param("from") String from, @Param("to") String to);

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
