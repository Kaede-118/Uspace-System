package com.kaede.uspace.order;

import com.kaede.uspace.common.export.ExportCache;
import com.kaede.uspace.common.export.XlsxWriter;
import com.kaede.uspace.order.dto.ExportTicketVo;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.mapper.OrderMapper;
import com.kaede.uspace.product.ProductOrderStatus;
import com.kaede.uspace.product.entity.ProductOrder;
import com.kaede.uspace.product.mapper.ProductOrderMapper;
import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import com.kaede.uspace.promotion.mapper.MonthlyCardOrderMapper;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 流水导出（模块 8，后台）。
 *
 * <p><b>导的是什么</b>：做账用得上的那些列，一行一条记录 ——
 * 不做「前端现场聚合」那一套（卡片上那些「在店 77 小时」之类的文案是给眼睛看的，
 * 拿去做透视会把人带偏）。金额写成真正的数字单元格，拖到 Excel 里直接就能求和。
 *
 * <p>⚠️ <b>「单表导出走全量列、精简留给总流水」</b>（2026-10-16 店主定的分界）：
 * 计时订单与商品单这两份<b>带齐库里的业务列</b>（门店 / 包场 ID、日场夜场的时长与金额、
 * 各项优惠拆分、状态码、核销人、人工调整留痕、备注、记录创建时间）——
 * 它是「这一单到底怎么回事」的底稿，用于查账、对账与追溯。
 * 而<b>要拿去算账（求和、透视），用 {@code ALL_ORDERS} 那份 12 列的总流水</b>：
 * 两种订单拼成一条时间线，只留单号 / 用户 / 金额 / 状态 / 支付信息。
 * 两者分工不同，别把精简当成这两份的默认形态 —— 那等于把明细藏起来。
 *
 * <p>⚠️ <b>不含门锁密码</b>：{@code passcode} 与 {@code one_time_passcode}
 * 一列都不导出 —— 与 {@code OrderVo} 是同一条边界。全量流水一旦落到谁都能打开的
 * Excel 里，密码就再也收不回来了。
 *
 * <p>⚠️ <b>时间列只留业务口径那两个</b>：「计费开始时间」（{@code start_time}）与
 * 「离场时间」（{@code end_time}）。审计口径的 {@code created_at}（记录进库时刻）
 * <b>不导</b> —— 它与前者在线上正常单里只差 1 秒（库里 61 单实测：33 单完全相同、
 * 28 单差 1 秒，同一事务里先后取的两次 now()），做账用不上；
 * 真要查「这单什么时候录进系统的」，库里那一列一直还在。
 *
 * <p>⚠️ <b>状态与支付方式都只导中文</b>（{@code OrderStatus#labelOf} /
 * {@code PaymentChannel#userLabelOf}）：枚举原值（{@code IN_USE} / {@code QR_UPLOAD}）
 * 留在库里 —— 这张表是给人看、给 Excel 按中文筛的，多一列原值只是噪音。
 *
 * <p>⚠️ <b>每条查询都有行数硬上限</b>（{@code MAX_ROWS}）：筛选条件写错
 * （比如时间范围传成 1970 到现在）时，没有上限就会把全表读进内存拼 xlsx。
 * 到上限时 {@code truncated} 置真，由前端提示用户缩小范围 ——
 * 宁可让他知道少了一截，也不能给一份看起来完整、实际缺行的账。
 */
@Service
public class OrderExportService {

    /** 单次导出行数上限。单店量级（几千单）远够，它的作用是兜住写错的筛选条件 */
    private static final int MAX_ROWS = 50_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    /** 计时订单的表头。顺序与 {@link #timingRow} 的赋值顺序必须一致 */
    static final List<String> TIMING_HEADERS = List.of(
            // 段一「一眼要看到的」：单号、谁、什么时候、多少钱、什么状态 —— 查账翻流水先看这些
            "订单号", "用户ID", "用户昵称", "门店ID", "包场ID",
            "计费开始时间", "离场时间", "在店时长(分钟)", "应付金额(元)",
            "状态", "状态码", "支付方式", "支付交易号", "到账时间",
            // 段二「要细看才用到的」：日夜场拆分与优惠明细（2026-10-16 店主让挪到后面，
            // 理由是「有效信息放前面、这张表才易读」——原先是紧跟在店时长之后的）
            "日场时长(分钟)", "日场金额(元)", "夜场时长(分钟)", "夜场金额(元)",
            "实收合计(元)", "优惠金额(元)", "月卡抵扣(元)", "活动减免(元)",
            // 段三「审计字段」：出问题时才回头查
            "核销管理员ID", "是否人工调整", "调整人ID", "调整时间", "调整原因",
            "备注", "记录创建时间");

    /** 商品购买单的表头，同上 */
    static final List<String> PRODUCT_HEADERS = List.of(
            "商品单号", "用户ID", "用户昵称", "商品ID", "商品名称", "单价(元)", "数量", "金额(元)",
            "状态", "状态码", "支付方式", "支付交易号", "到账时间", "记录创建时间");

    /**
     * 「总流水」的表头：把计时订单与商品单拼成同一条时间线（2026-10-16 店主提的）。
     *
     * <p>两张表的字段本来对不上，所以这是一套<b>双方都要迁就</b>的列：
     * 计时订单没有商品，就写死 {@code 0}（商品 ID）与「计时」（商品名称），
     * 单价与数量留空；商品单则没有日场 / 夜场那套时长金额。
     *
     * <p>第一列叫「单号」而不是「商品单号」：这一列里两种单号混着
     *（{@code OD} 开头是计时、{@code PD} 开头是商品），前缀本身就能区分类型 ——
     * 与 {@code biz_trade_log} 用 {@code target_no} 记事的思路一致。
     */
    static final List<String> ALL_HEADERS = List.of(
            "类型", "单号", "用户ID", "用户昵称", "商品ID", "商品名称",
            "单价(元)", "数量", "金额(元)",
            "状态", "支付方式", "支付交易号", "到账时间");

    private final OrderMapper orderMapper;
    private final ProductOrderMapper productOrderMapper;
    private final MonthlyCardOrderMapper monthlyCardOrderMapper;
    private final BookingMapper bookingMapper;
    private final SysUserMapper sysUserMapper;
    private final ExportCache exportCache;

    public OrderExportService(OrderMapper orderMapper,
                              ProductOrderMapper productOrderMapper,
                              MonthlyCardOrderMapper monthlyCardOrderMapper,
                              BookingMapper bookingMapper,
                              SysUserMapper sysUserMapper,
                              ExportCache exportCache) {
        this.orderMapper = orderMapper;
        this.productOrderMapper = productOrderMapper;
        this.monthlyCardOrderMapper = monthlyCardOrderMapper;
        this.bookingMapper = bookingMapper;
        this.sysUserMapper = sysUserMapper;
        this.exportCache = exportCache;
    }

    /**
     * 按类型导出。筛选条件与后台列表同口径，但导全量。
     *
     * @param request 导出类型与筛选条件
     * @param adminId 当前管理员 ID（缓存归属校验用）
     * @return 下载票据
     */
    public ExportTicketVo export(com.kaede.uspace.order.dto.ExportRequest request, long adminId) {
        if ("ALL_ORDERS".equals(request.getType())) {
            return exportAllOrders(adminId);
        }
        if ("PRODUCT_ORDER".equals(request.getType())) {
            List<ProductOrder> orders = productOrderMapper.selectForExport(
                    request.getUserId(), trimToNull(request.getStatus()), MAX_ROWS);
            Map<Long, String> nicknames = nicknameMap(
                    orders.stream().map(ProductOrder::getUserId).toList());
            List<List<Object>> rows = new ArrayList<>(orders.size());
            orders.forEach(o -> rows.add(productRow(o, nicknames)));
            return store("商品订单流水", "商品订单", PRODUCT_HEADERS, rows, adminId);
        }
        List<Order> orders = orderMapper.selectForExport(
                request.getUserId(), trimToNull(request.getStatus()),
                request.getFrom(), request.getTo(), request.getAdjusted(), MAX_ROWS);
        Map<Long, String> nicknames = nicknameMap(
                orders.stream().map(Order::getUserId).toList());
        List<List<Object>> rows = new ArrayList<>(orders.size());
        orders.forEach(o -> rows.add(timingRow(o, nicknames)));
        return store("计时订单流水", "计时订单", TIMING_HEADERS, rows, adminId);
    }

    /**
     * 导出「总流水」：计时订单与商品单合成一张表。
     *
     * <p>⚠️ <b>不接筛选条件</b>：两张表能筛的维度不一样（计时单有时间范围与
     * 「只看人工调整」，商品单只有状态），混着传只会让「这次导的到底是什么口径」
     * 说不清。要按条件导，用上面那两个各自带筛选的类型。
     *
     * <p>商品单那一侧<b>照旧排除「已关闭」</b>（{@code status} 传 null 即触发，
     * 见 {@code ProductOrderMapper#selectForExport}）—— 取消未付款的单一分钱没动。
     *
     * <p>两段合成后按<b>记录进系统的时刻倒序</b>排（{@code created_at}：两张表都有、
     * 语义也一致），看起来就是一条按时间铺开的流水；缺这个时刻的排到最后。
     *
     * @param adminId 当前管理员 ID
     * @return 下载票据
     */
    private ExportTicketVo exportAllOrders(long adminId) {
        List<Order> timing = orderMapper.selectForExport(null, null, null, null, null, MAX_ROWS);
        List<ProductOrder> products = productOrderMapper.selectForExport(null, null, MAX_ROWS);
        // 月卡与包场没有「带筛选的全量查询」写法（总流水本来也不接筛选），
        // 借 MyBatis-Plus 的分页壳子取前 MAX_ROWS 条 —— 它同时会带上逻辑删除条件
        List<MonthlyCardOrder> cards = monthlyCardOrderMapper
                .selectPage(new Page<>(1, MAX_ROWS), null).getRecords();
        List<Booking> bookings = bookingMapper
                .selectPage(new Page<>(1, MAX_ROWS), null).getRecords();

        Map<Long, String> nicknames = nicknameMap(Stream.concat(
                Stream.concat(timing.stream().map(Order::getUserId),
                        products.stream().map(ProductOrder::getUserId)),
                Stream.concat(cards.stream().map(MonthlyCardOrder::getUserId),
                        bookings.stream().map(Booking::getHostUserId))).toList());

        // 四种实体拼不进同一个 List，所以先各自成行、顺带带上排序键，再一起排
        record Line(LocalDateTime at, List<Object> cells) {
        }
        List<Line> lines = new ArrayList<>(
                timing.size() + products.size() + cards.size() + bookings.size());
        timing.forEach(o -> lines.add(new Line(o.getCreatedAt(), allRow(o, nicknames))));
        products.forEach(o -> lines.add(new Line(o.getCreatedAt(), allRow(o, nicknames))));
        cards.forEach(o -> lines.add(new Line(o.getCreatedAt(), allRow(o, nicknames))));
        bookings.forEach(o -> lines.add(new Line(o.getCreatedAt(), allRow(o, nicknames))));
        lines.sort(Comparator.comparing(Line::at, Comparator.nullsLast(Comparator.reverseOrder())));

        List<List<Object>> rows = lines.stream().map(Line::cells).toList();
        return store("总流水", "总流水", ALL_HEADERS, rows, adminId);
    }

    /** 总流水里<b>计时订单</b>的一行：商品那几列按约定写死或留空。 */
    static List<Object> allRow(Order o, Map<Long, String> nicknames) {
        List<Object> row = new ArrayList<>(ALL_HEADERS.size());
        row.add("计时");
        row.add(o.getOrderNo());
        row.add(o.getUserId());
        row.add(nicknames.get(o.getUserId()));
        row.add(0);
        row.add("计时");
        row.add(null);
        row.add(null);
        row.add(o.getTotalAmount());
        row.add(OrderStatus.labelOf(o.getStatus()));
        row.add(PaymentChannel.userLabelOf(o.getPaymentMethod()));
        row.add(o.getPaymentNo());
        row.add(stamp(o.getPaidAt()));
        return row;
    }

    /** 总流水里<b>商品单</b>的一行。 */
    static List<Object> allRow(ProductOrder o, Map<Long, String> nicknames) {
        List<Object> row = new ArrayList<>(ALL_HEADERS.size());
        row.add("商品");
        row.add(o.getOrderNo());
        row.add(o.getUserId());
        row.add(nicknames.get(o.getUserId()));
        row.add(o.getProductId());
        row.add(o.getProductName());
        row.add(o.getUnitPrice());
        row.add(o.getQuantity());
        row.add(o.getAmount());
        row.add(ProductOrderStatus.labelOf(o.getStatus()));
        row.add(PaymentChannel.userLabelOf(o.getPaymentMethod()));
        row.add(o.getPaymentNo());
        row.add(stamp(o.getPaidAt()));
        return row;
    }

    /** 总流水里<b>月卡购买单</b>的一行（2026-10-16 按店主的要求并入）。 */
    static List<Object> allRow(MonthlyCardOrder o, Map<Long, String> nicknames) {
        List<Object> row = new ArrayList<>(ALL_HEADERS.size());
        row.add("月卡");
        row.add(o.getOrderNo());
        row.add(o.getUserId());
        row.add(nicknames.get(o.getUserId()));
        row.add(0);
        row.add("月卡");
        row.add(null);
        row.add(null);
        row.add(o.getPrice());
        row.add(MonthlyCardStatus.labelOf(o.getStatus()));
        row.add(PaymentChannel.userLabelOf(o.getPaymentMethod()));
        row.add(o.getPaymentNo());
        row.add(stamp(o.getPaidAt()));
        return row;
    }

    /**
     * 总流水里<b>包场</b>的一行（同上）。
     *
     * <p>⚠️ 金额写的是<b>包场费原价</b>，不是「减去退款后的净额」——
     * 已退款的场次状态列会写「已退款」，按状态筛一下就知道钱退回去了；
     * 若这里直接写净额，反倒看不出这场原本收了多少。
     */
    static List<Object> allRow(Booking o, Map<Long, String> nicknames) {
        List<Object> row = new ArrayList<>(ALL_HEADERS.size());
        row.add("包场");
        row.add(o.getBookingNo());
        row.add(o.getHostUserId());
        row.add(nicknames.get(o.getHostUserId()));
        row.add(0);
        row.add("包场");
        row.add(null);
        row.add(null);
        row.add(o.getPrice());
        row.add(BookingStatus.labelOf(o.getStatus()));
        row.add(PaymentChannel.userLabelOf(o.getPaymentMethod()));
        row.add(o.getPaymentNo());
        row.add(stamp(o.getPaidAt()));
        return row;
    }

    /** 计时订单的一行，顺序与 {@link #TIMING_HEADERS} 对齐。 */
    static List<Object> timingRow(Order o, Map<Long, String> nicknames) {
        List<Object> row = new ArrayList<>(TIMING_HEADERS.size());
        // ⚠️ 顺序必须与 TIMING_HEADERS 逐格对齐（错位不报错，只会让整行串列）
        row.add(o.getOrderNo());
        row.add(o.getUserId());
        row.add(nicknames.get(o.getUserId()));
        row.add(o.getStoreId());
        row.add(o.getBookingId());
        row.add(stamp(o.getStartTime()));
        row.add(stamp(o.getEndTime()));
        row.add(o.getStayMinutes());
        row.add(o.getPayableAmount());
        row.add(OrderStatus.labelOf(o.getStatus()));
        row.add(o.getStatus());
        row.add(PaymentChannel.userLabelOf(o.getPaymentMethod()));
        row.add(o.getPaymentNo());
        row.add(stamp(o.getPaidAt()));
        row.add(o.getDayMinutes());
        row.add(o.getDayAmount());
        row.add(o.getNightMinutes());
        row.add(o.getNightAmount());
        row.add(o.getTotalAmount());
        row.add(o.getDiscountAmount());
        row.add(o.getCardFreeAmount());
        row.add(o.getActivityFreeAmount());
        row.add(o.getConfirmedBy());
        row.add(o.getAdjusted());
        row.add(o.getAdjustedBy());
        row.add(stamp(o.getAdjustedAt()));
        row.add(o.getAdjustReason());
        row.add(o.getRemark());
        row.add(stamp(o.getCreatedAt()));
        return row;
    }

    /** 商品购买单的一行。 */
    static List<Object> productRow(ProductOrder o, Map<Long, String> nicknames) {
        List<Object> row = new ArrayList<>(PRODUCT_HEADERS.size());
        row.add(o.getOrderNo());
        row.add(o.getUserId());
        row.add(nicknames.get(o.getUserId()));
        row.add(o.getProductId());
        row.add(o.getProductName());
        row.add(o.getUnitPrice());
        row.add(o.getQuantity());
        row.add(o.getAmount());
        row.add(ProductOrderStatus.labelOf(o.getStatus()));
        row.add(o.getStatus());
        row.add(PaymentChannel.userLabelOf(o.getPaymentMethod()));
        row.add(o.getPaymentNo());
        row.add(stamp(o.getPaidAt()));
        row.add(stamp(o.getCreatedAt()));
        return row;
    }

    /**
     * 批量取「下单人昵称」（昵称优先、回落登录名）。
     *
     * <p>与 {@code OrderService#attachUserNames} 同一个口径、同一个工具方法 ——
     * 导出的表与页面必须认得是同一个人。
     *
     * <p>查不到的用户（已注销）留 null → Excel 里是空格；这里不编一个名字，
     * 免得把「查不到」伪装成「叫这个名字」。
     *
     * @param userIds 本批导出涉及的用户 ID（可含 null 与重复）
     * @return 用户 ID → 展示名
     */
    private Map<Long, String> nicknameMap(List<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, OrderService::displayName));
    }

    /** 生成 xlsx 并放进缓存，返回票据。 */
    private ExportTicketVo store(String prefix, String sheetName,
                                 List<String> headers, List<List<Object>> rows, long adminId) {
        byte[] content = XlsxWriter.write(sheetName, headers, rows);
        String fileName = prefix + "_" + LocalDateTime.now().format(FILE_STAMP) + ".xlsx";
        ExportTicketVo vo = new ExportTicketVo();
        vo.setToken(exportCache.put(fileName, content, adminId));
        vo.setFileName(fileName);
        vo.setRows(rows.size());
        vo.setTruncated(rows.size() >= MAX_ROWS);
        return vo;
    }

    /** 时间列统一成 {@code yyyy-MM-dd HH:mm:ss}；为空时留空格而不是 "null"。 */
    private static String stamp(LocalDateTime time) {
        return time == null ? null : time.format(STAMP);
    }

    /** 空串当没传 —— 与后台列表的筛选口径一致。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
