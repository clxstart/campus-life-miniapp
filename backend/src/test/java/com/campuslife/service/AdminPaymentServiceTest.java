package com.campuslife.service;

import com.campuslife.entity.Shop;
import com.campuslife.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Finding 06/07/08：列表 SQL 数有界、关键词受限且在库内分页、导出完整或明确拒绝 */
class AdminPaymentServiceTest {
    private final ShopMapper shops = mock(ShopMapper.class);
    private final AdminPaymentMapper payments = mock(AdminPaymentMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final PaymentRecordMapper records = mock(PaymentRecordMapper.class);
    private AdminPaymentService service;

    @BeforeEach
    void setUp() {
        service = new AdminPaymentService(mock(UserOrderMapper.class), records, mock(RefundRecordMapper.class), shops, users, mock(PayService.class), payments);
        Shop shop = new Shop(); shop.setId(1L);
        when(shops.selectList(any())).thenReturn(List.of(shop));
    }

    private static List<Map<String, Object>> rows(long fromId, int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", fromId - i); r.put("outTradeNo", "T" + (fromId - i)); r.put("amountCents", 1234); r.put("userName", "u");
            list.add(r);
        }
        return list;
    }

    @Test
    void listUsesFixedNumberOfQueriesRegardlessOfPageSize() {
        when(payments.page(any(), any(), any(), any(), any(), anyBoolean(), eq(100), eq(0L))).thenReturn(rows(1000, 100));
        when(payments.count(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(5000L);
        Map<String, Object> out = service.list(9L, "school", "", null, "", "", "", 1, 100);
        assertThat((List<?>) out.get("list")).hasSize(100);
        assertThat(out.get("total")).isEqualTo(5000L);
        // 旧实现每行 3 次查询（用户/店铺/流水），这里与行数无关：1 次店铺范围 + 1 次分页 + 1 次计数
        verify(payments, times(1)).page(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyLong());
        verify(payments, times(1)).count(any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(users, records);
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) ((List<?>) out.get("list")).get(0);
        assertThat(first.get("amount").toString()).isEqualTo("12.34");
    }

    @Test
    void keywordIsBoundedEscapedAndClassified() {
        assertThatThrownBy(() -> service.list(9L, "school", "", null, "", "", "x".repeat(33), 1, 20)).isInstanceOf(IllegalArgumentException.class);
        when(payments.page(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyLong())).thenReturn(List.of());
        service.list(9L, "school", "", null, "", "", "4200001234567890", 1, 20);
        // 单号形态只走前缀匹配（可索引），不做昵称包含匹配
        verify(payments).page(any(), any(), any(), any(), eq("4200001234567890"), eq(false), anyInt(), anyLong());
        service.list(9L, "school", "", null, "", "", "林_%", 1, 20);
        verify(payments).page(any(), any(), any(), any(), eq("林\\_\\%"), eq(true), anyInt(), anyLong());
    }

    @Test
    void deepPagingIsRefused() {
        assertThatThrownBy(() -> service.list(9L, "school", "", null, "", "", "", 1000, 100)).hasMessageContaining("翻页过深");
    }

    @Test
    void noAccessibleShopReturnsEmptyWithoutQuerying() {
        when(shops.selectList(any())).thenReturn(List.of());
        assertThat((List<?>) service.list(9L, "school", "", null, "", "", "", 1, 20).get("list")).isEmpty();
        verifyNoInteractions(payments);
    }

    @Test
    void exportOverLimitIsRefusedNotTruncated() {
        when(payments.count(any(), any(), any(), any(), any(), anyBoolean())).thenReturn((long) AdminPaymentService.MAX_EXPORT_ROWS + 1);
        assertThatThrownBy(() -> service.exportCount(9L, "school", "", null, "", "", ""))
            .isInstanceOf(AdminPaymentService.ExportTooLargeException.class).hasMessageContaining("上限");
    }

    @Test
    void exportStreamsAllRowsAcrossBatchesWithKeysetCursor() throws Exception {
        int batch = AdminPaymentService.EXPORT_BATCH;
        when(payments.exportBatch(any(), any(), any(), any(), any(), anyBoolean(), isNull(), eq(batch))).thenReturn(rows(1200, batch));
        when(payments.exportBatch(any(), any(), any(), any(), any(), anyBoolean(), eq(1200L - batch + 1), eq(batch))).thenReturn(rows(1200 - batch, batch));
        when(payments.exportBatch(any(), any(), any(), any(), any(), anyBoolean(), eq(1200L - 2L * batch + 1), eq(batch))).thenReturn(rows(1200 - 2L * batch, 7));
        StringWriter out = new StringWriter();
        long written = service.exportCsv(9L, "school", "", null, "", "", "", out);
        assertThat(written).isEqualTo(2L * batch + 7);
        // 表头 + 每行一条
        assertThat(out.toString().split("\n")).hasSize((int) written + 1);
    }

    @Test
    void csvCellsAreSafeAgainstFormulaInjection() {
        assertThat(AdminPaymentService.csv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(AdminPaymentService.csv("林知夏")).isEqualTo("\"林知夏\"");
    }
}
