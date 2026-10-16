package com.kaede.uspace.order;

import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.product.entity.ProductOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水导出的两条纯逻辑：<b>列对齐</b>与<b>昵称填充</b>。
 *
 * <p>⚠️ 为什么专门测「每行的格子数 == 表头数」：加一列时表头改了、行里忘了加
 * （或反过来），<b>不会报任何错</b> —— {@code XlsxWriter} 按表头宽度遍历，
 * 少了就补一格空白。表现是最后几列串位或空白，而做账的人会以为「这些单就是这样」。
 * 这是本次加「用户昵称」列时最容易踩的一处，所以钉住它。
 *
 * <p>不需要 fake Mapper：只测行构造这一段纯函数（查询与落库不在这里）。
 */
class OrderExportServiceTests {

    private static Order sampleOrder() {
        Order o = new Order();
        o.setOrderNo("OD202610162217013921");
        o.setUserId(4690L);
        o.setStoreId(1L);
        o.setStartTime(LocalDateTime.of(2026, 10, 16, 22, 17, 1));
        o.setEndTime(LocalDateTime.of(2026, 10, 16, 23, 30, 0));
        o.setStayMinutes(73);
        o.setDayMinutes(0);
        o.setNightMinutes(73);
        o.setTotalAmount(new BigDecimal("14.00"));
        o.setPayableAmount(new BigDecimal("14.00"));
        o.setStatus("PENDING_PAYMENT");
        o.setCreatedAt(LocalDateTime.of(2026, 10, 16, 22, 17, 2));
        return o;
    }

    private static ProductOrder sampleProductOrder() {
        ProductOrder p = new ProductOrder();
        p.setOrderNo("PD202610162217013921");
        p.setUserId(4690L);
        p.setProductId(7L);
        p.setProductName("可乐");
        p.setUnitPrice(new BigDecimal("3.50"));
        p.setQuantity(2);
        p.setAmount(new BigDecimal("7.00"));
        p.setStatus("PAID");
        p.setCreatedAt(LocalDateTime.of(2026, 10, 16, 22, 17, 2));
        return p;
    }

    @Test
    @DisplayName("计时订单：行的格子数与表头严格相等（错位不会报错，所以钉住）")
    void timingRow_alignsWithHeaders() {
        List<Object> row = OrderExportService.timingRow(sampleOrder(), Map.of(4690L, "蓝色大肥鱼"));

        assertEquals(OrderExportService.TIMING_HEADERS.size(), row.size(),
                "表头有 " + OrderExportService.TIMING_HEADERS.size() + " 列，这行给了 " + row.size() + " 个值");
    }

    @Test
    @DisplayName("商品单：行的格子数与表头严格相等")
    void productRow_alignsWithHeaders() {
        List<Object> row = OrderExportService.productRow(sampleProductOrder(), Map.of(4690L, "蓝色大肥鱼"));

        assertEquals(OrderExportService.PRODUCT_HEADERS.size(), row.size());
    }

    @Test
    @DisplayName("昵称填在「用户昵称」那一列；查不到时留空，不编一个名字")
    void rows_fillNicknameAtTheRightColumn() {
        int index = OrderExportService.TIMING_HEADERS.indexOf("用户昵称");
        assertEquals(2, index, "它应当紧跟在「用户ID」之后 —— 移动了位置就更新这条断言");

        List<Object> filled = OrderExportService.timingRow(sampleOrder(), Map.of(4690L, "蓝色大肥鱼"));
        assertEquals("蓝色大肥鱼", filled.get(index));

        List<Object> missing = OrderExportService.timingRow(sampleOrder(), Map.of());
        assertNull(missing.get(index), "查不到用户时该格留空，免得把「查不到」伪装成「叫这个名字」");
    }

    @Test
    @DisplayName("单表导出是全量列：计费明细与审计字段都在（精简的那份是「总流水」）")
    void timingHeaders_keepFullDetail() {
        // 这几列是「全量」的标志 —— 总流水那份刻意不带它们。
        // 用「包含」而不是逐字相等：以后实体加了字段，这张表跟着加是应该的，
        // 不该因此让用例变红；但少掉任何一列都要拦下来
        for (String must : List.of("门店ID", "包场ID", "在店时长(分钟)",
                "日场时长(分钟)", "日场金额(元)", "夜场时长(分钟)", "夜场金额(元)",
                "实收合计(元)", "优惠金额(元)", "月卡抵扣(元)", "活动减免(元)", "应付金额(元)",
                "状态码", "核销管理员ID", "是否人工调整", "调整人ID", "调整时间", "调整原因",
                "备注", "记录创建时间")) {
            assertTrue(OrderExportService.TIMING_HEADERS.contains(must),
                    "「" + must + "」属于全量导出，不该缺");
        }
    }

    @Test
    @DisplayName("总流水：两种订单的行格数都等于表头，计时单按约定写死商品那几列")
    void allRows_alignAndFillPlaceholders() {
        assertEquals(13, OrderExportService.ALL_HEADERS.size(), "总流水是 13 列（含开头的「类型」）");
        assertEquals("类型", OrderExportService.ALL_HEADERS.get(0), "第一列就是类型：计时 / 商品 / 月卡 / 包场");

        List<Object> timing = OrderExportService.allRow(sampleOrder(), Map.of(4690L, "蓝色大肥鱼"));
        assertEquals(OrderExportService.ALL_HEADERS.size(), timing.size());
        assertEquals("计时", timing.get(0), "类型列要写明这一行是什么单");
        // 计时订单没有商品，这几格按约定写死或留空 —— 它们让「这一行是什么单」一眼可辨
        assertEquals(0, ((Number) timing.get(OrderExportService.ALL_HEADERS.indexOf("商品ID"))).intValue());
        assertEquals("计时", timing.get(OrderExportService.ALL_HEADERS.indexOf("商品名称")));
        assertNull(timing.get(OrderExportService.ALL_HEADERS.indexOf("单价(元)")));
        assertNull(timing.get(OrderExportService.ALL_HEADERS.indexOf("数量")));
        assertEquals("蓝色大肥鱼", timing.get(OrderExportService.ALL_HEADERS.indexOf("用户昵称")));

        List<Object> product = OrderExportService.allRow(sampleProductOrder(), Map.of(4690L, "蓝色大肥鱼"));
        assertEquals(OrderExportService.ALL_HEADERS.size(), product.size());
        assertEquals(7, ((Number) product.get(OrderExportService.ALL_HEADERS.indexOf("商品ID"))).intValue());
        assertEquals("可乐", product.get(OrderExportService.ALL_HEADERS.indexOf("商品名称")));
    }

    @Test
    @DisplayName("两个时间列都填上了：计费开始时间与离场时间")
    void timingRow_fillsBothTimestamps() {
        List<Object> row = OrderExportService.timingRow(sampleOrder(), Map.of());

        assertEquals("2026-10-16 22:17:01",
                row.get(OrderExportService.TIMING_HEADERS.indexOf("计费开始时间")));
        assertEquals("2026-10-16 23:30:00",
                row.get(OrderExportService.TIMING_HEADERS.indexOf("离场时间")));
    }
}
