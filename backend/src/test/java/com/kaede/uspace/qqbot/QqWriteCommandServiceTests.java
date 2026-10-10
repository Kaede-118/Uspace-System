package com.kaede.uspace.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.device.DeviceService;
import com.kaede.uspace.device.FakeDeviceMapper;
import com.kaede.uspace.device.FakeEquipmentTypeMapper;
import com.kaede.uspace.device.entity.Device;
import com.kaede.uspace.device.entity.EquipmentType;
import com.kaede.uspace.notice.FakeNoticeMapper;
import com.kaede.uspace.notice.NoticeService;
import com.kaede.uspace.product.FakeProductMapper;
import com.kaede.uspace.product.FakeProductOrderMapper;
import com.kaede.uspace.product.ProductProperties;
import com.kaede.uspace.product.ProductOrderStatus;
import com.kaede.uspace.product.ProductService;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.product.entity.ProductOrder;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.UserRole;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QqWriteCommandService} 的单元测试 —— <b>覆盖「调整库存」与「调整机台状况」
 * 这两条</b>（另外几条写指令要组装整套订单服务才测得了，那是将来单独一件事）。
 *
 * <p>它守的核心是一条权限：<b>只有管理员能改库存、改机台状况</b>。两条都判错的
 * 两种方式都不报错 —— 放过非管理员（数据被不该改的人改掉）或挡住管理员
 * （功能形同不存在，而文案还坚称「仅限管理员」）。机台那条还多守一条：
 * <b>同名多台时不许挑一台改</b>。
 *
 * <p>纯单测、不连库：出站由 {@link RecordingClient} 记下，用户、商品与机台
 * 各由现成的假 Mapper 顶替（机台那组用<b>真的</b> {@code DeviceService} 配假 Mapper，
 * 与 {@code DeviceServiceTests} 同一套组装）。本类用不到的订单 / 取码 / 凭证
 * 三个依赖按本项目的既有惯例传 {@code null}（见 {@code QqPaymentProofServiceTests}
 * 的同类做法）—— 真被碰到就是 NPE，是个响亮的失败，不会静默走错路。
 */
class QqWriteCommandServiceTests {

    /** 管理员的 QQ 与用户 ID。<b>刻意取两个不同的数</b>：写反了用例才会红 */
    private static final Long ADMIN_QQ = 2198047522L;

    private static final Long ADMIN_USER_ID = 1249L;

    /** 普通用户的 QQ 与用户 ID，同样刻意不同 */
    private static final Long NORMAL_QQ = 1241397393L;

    private static final Long NORMAL_USER_ID = 1001L;

    /** 一个从没注册过的 QQ */
    private static final Long STRANGER_QQ = 1000000001L;

    private static final Long GROUP_ID = 10001L;

    /** 机台那一组用例用：测试门店与字典里的「拍拍机」类型 */
    private static final Long STORE_ID = 1L;

    private static final Long DEVICE_TYPE_ID = 10L;

    /** 时刻固定，用例因此与运行时刻无关 */
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-09T06:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private RecordingClient client;

    private QqbotProperties properties;

    private FakeSysUserMapper userMapper;

    private FakeProductMapper productMapper;

    /** 商品单假表：取消用例要断言被取消的单子确实关掉了、别人的单子没动 */
    private FakeProductOrderMapper orderMapper;

    /** 机台那一组用例用：真的 {@code DeviceService} 配假 Mapper（同 DeviceServiceTests 的组装） */
    private FakeDeviceMapper deviceMapper;

    private QqWriteCommandService service;

    @BeforeEach
    void setUp() {
        client = new RecordingClient();

        properties = new QqbotProperties();
        properties.setWriteEnabled(true);
        properties.getBroadcast().setEnabled(true);
        // 冷却设 0：它挡的是连点，与权限无关，留着只会让用例之间互相干扰
        properties.setWriteCommandCooldown(Duration.ZERO);

        userMapper = new FakeSysUserMapper();
        seedUser(ADMIN_USER_ID, ADMIN_QQ, UserRole.ADMIN);
        seedUser(NORMAL_USER_ID, NORMAL_QQ, UserRole.USER);

        productMapper = new FakeProductMapper();
        orderMapper = new FakeProductOrderMapper();
        ProductService productService = new ProductService(productMapper.asMapper(),
                orderMapper.asMapper(), new ProductProperties(), event -> { });

        deviceMapper = new FakeDeviceMapper();
        DeviceService deviceService = newDeviceService(deviceMapper);

        service = new QqWriteCommandService(properties, client, userMapper.asMapper(),
                null, null, productService, deviceService, null, null, null,
                new WebProperties(), CLOCK);
    }

    // ==================================================================
    // 权限：本类最要紧的一组
    // ==================================================================

    @Test
    @DisplayName("⚠️ 守门：非管理员发库存指令被拒，且库存一个数都没动")
    void adjustStock_rejectsNonAdmin() {
        Product cola = seedProduct("可乐", 20);

        service.adjustStock(GROUP_ID, NORMAL_QQ, "可乐", 5);

        assertEquals(20, productMapper.get(cola.getId()).getStock(),
                "⚠️ 权限判错不会报任何错，只会让库存被不该改的人改掉 —— "
                        + "这是本类存在的全部理由");
        assertTrue(singleMessage().contains("仅限管理员"), singleMessage());
    }

    @Test
    @DisplayName("管理员发库存指令：库存被设成新值，回复报出「原 → 新」")
    void adjustStock_adminSetsStock() {
        Product cola = seedProduct("可乐", 20);

        service.adjustStock(GROUP_ID, ADMIN_QQ, "可乐", 5);

        assertEquals(5, productMapper.get(cola.getId()).getStock(),
                "传的是新的库存值，不是增减量");
        String text = singleMessage();
        assertTrue(text.contains("20 件"), "原值要报 —— 发错了全靠它当场发现：" + text);
        assertTrue(text.contains("→ 5 件"), "新值：" + text);
    }

    @Test
    @DisplayName("isAdmin：按库里的角色判，未绑定的 QQ 一律不是")
    void isAdmin_readsRoleFromDatabase() {
        assertTrue(service.isAdmin(ADMIN_QQ));
        assertFalse(service.isAdmin(NORMAL_QQ), "普通用户不是管理员");
        assertFalse(service.isAdmin(STRANGER_QQ), "没绑过号的更不是");
        assertFalse(service.isAdmin(null), "null 也要安全地返回 false，不能抛");
    }

    // ==================================================================
    // 其余拒绝与失败分支
    // ==================================================================

    @Test
    @DisplayName("没绑过号的 QQ：回「尚未绑定」而不是「仅限管理员」")
    void adjustStock_unboundQqGetsNotBound() {
        service.adjustStock(GROUP_ID, STRANGER_QQ, "可乐", 5);

        assertTrue(singleMessage().contains("尚未绑定"),
                "他先要知道的是「你还没绑号」，而不是一句关于权限的话：" + singleMessage());
    }

    @Test
    @DisplayName("商品名对不上：原样回显那个名字")
    void adjustStock_unknownProductEchoesName() {
        service.adjustStock(GROUP_ID, ADMIN_QQ, "不存在的商品", 5);

        assertTrue(singleMessage().contains("不存在的商品"),
                "名字切得对不对，他一看回显便知：" + singleMessage());
    }

    @Test
    @DisplayName("⚠️ 播报关掉时拒绝执行，库存不动（与另几条写指令同一条纪律）")
    void adjustStock_refusesWhenBroadcastDisabled() {
        properties.getBroadcast().setEnabled(false);
        Product cola = seedProduct("可乐", 20);

        service.adjustStock(GROUP_ID, ADMIN_QQ, "可乐", 5);

        assertEquals(20, productMapper.get(cola.getId()).getStock(),
                "群里看不到播报，就等于一次没人看见的操作 —— 那还不如去网页端做");
        assertTrue(singleMessage().contains("播报未开启"), singleMessage());
    }

    @Test
    @DisplayName("写指令总开关关掉时同样拒绝，库存不动")
    void adjustStock_refusesWhenWriteDisabled() {
        properties.setWriteEnabled(false);
        Product cola = seedProduct("可乐", 20);

        service.adjustStock(GROUP_ID, ADMIN_QQ, "可乐", 5);

        assertEquals(20, productMapper.get(cola.getId()).getStock());
        assertTrue(singleMessage().contains("未开放"), singleMessage());
    }

    // ==================================================================
    // 机台状况（fw拍拍机 1 号维护中，同样仅管理员）
    // ==================================================================

    @Test
    @DisplayName("⚠️ 守门：非管理员发机台状况指令被拒，且状况一个字段都没动")
    void adjustDeviceStatus_rejectsNonAdmin() {
        Device device = seedDevice(101L, "拍拍机 1 号");

        service.adjustDeviceStatus(GROUP_ID, NORMAL_QQ, "拍拍机 1 号", "MAINTAINING");

        assertEquals("NORMAL", deviceMapper.get(device.getId()).getStatus(),
                "⚠️ 权限判错不会报任何错，只会让机台被不该动的人挂上「维护中」——"
                        + "随后首页公告还会替他把这条消息播出去");
        assertTrue(singleMessage().contains("仅限管理员"), singleMessage());
    }

    @Test
    @DisplayName("管理员发机台状况指令：状况被改掉，回复报出「原 → 新」")
    void adjustDeviceStatus_adminChangesStatus() {
        Device device = seedDevice(101L, "拍拍机 1 号");

        service.adjustDeviceStatus(GROUP_ID, ADMIN_QQ, "拍拍机 1 号", "MAINTAINING");

        assertEquals("MAINTAINING", deviceMapper.get(device.getId()).getStatus());
        String text = singleMessage();
        assertTrue(text.contains("拍拍机 1 号"), "改了哪台要说清楚：" + text);
        assertTrue(text.contains("良好"), "原值要报 —— 发错了全靠它当场发现：" + text);
        assertTrue(text.contains("维护中"), "新值：" + text);
    }

    @Test
    @DisplayName("⚠️ 守门：同名多台时拒绝执行，任何一台都不许被改")
    void adjustDeviceStatus_rejectsAmbiguousName() {
        Device first = seedDevice(101L, "拍拍机");
        Device second = seedDevice(102L, "拍拍机");

        service.adjustDeviceStatus(GROUP_ID, ADMIN_QQ, "拍拍机", "MAINTAINING");

        assertEquals("NORMAL", deviceMapper.get(first.getId()).getStatus(), "不许挑一台改");
        assertEquals("NORMAL", deviceMapper.get(second.getId()).getStatus());
        assertTrue(singleMessage().contains("有多台机台都叫"), singleMessage());
    }

    @Test
    @DisplayName("机台名对不上时回复「未找到」，不误改任何一台")
    void adjustDeviceStatus_notFound() {
        Device device = seedDevice(101L, "拍拍机 1 号");

        service.adjustDeviceStatus(GROUP_ID, ADMIN_QQ, "拍拍机", "MAINTAINING");

        assertEquals("NORMAL", deviceMapper.get(device.getId()).getStatus(),
                "名字必须完全一致 —— 模糊匹配会改到别的机器上");
        assertTrue(singleMessage().contains("未找到名为"), singleMessage());
    }

    // ==================================================================
    // fw取消（2026-10-10）
    // ==================================================================

    @Test
    @DisplayName("⚠️ 守门：计时订单在群里不能取消（欠费不能自消），文案指路 fw结账")
    void cancelOrder_refusesTimingOrder() {
        service.cancelOrder(GROUP_ID, NORMAL_QQ, "OD202610101430251739");

        String reply = singleMessage();
        assertTrue(reply.contains("不支持"), reply);
        assertTrue(reply.contains("fw结账"),
                "「不能取消」之外必须说清去处，否则用户只知道不行：" + reply);
    }

    @Test
    @DisplayName("取消：前缀认不出的单号回一句指路，不猜也不静默")
    void cancelOrder_rejectsUnknownPrefix() {
        service.cancelOrder(GROUP_ID, NORMAL_QQ, "XX202610101430251739");

        assertTrue(singleMessage().contains("没认出"), singleMessage());
    }

    @Test
    @DisplayName("取消：自己的待支付商品单被关掉，回复报「库存已释放」")
    void cancelOrder_cancelsOwnPendingProductOrder() {
        ProductOrder order = seedProductOrder("PD202610101430251739", NORMAL_USER_ID,
                ProductOrderStatus.PENDING_PAYMENT.name());

        service.cancelOrder(GROUP_ID, NORMAL_QQ, "PD202610101430251739");

        assertEquals(ProductOrderStatus.CLOSED.name(), order.getStatus(),
                "取消的落点是把单子关掉（与网页端同一个 closePending）");
        String reply = singleMessage();
        assertTrue(reply.contains("已取消未付款"), reply);
        assertTrue(reply.contains("库存已释放"), "要说清取消之后释放了什么：" + reply);
    }

    @Test
    @DisplayName("⚠️ 守门：别人的商品单取消不了 —— 按「没找到」处理，防枚举单号")
    void cancelOrder_refusesOthersOrder() {
        ProductOrder order = seedProductOrder("PD202610101430251739", ADMIN_USER_ID,
                ProductOrderStatus.PENDING_PAYMENT.name());

        service.cancelOrder(GROUP_ID, NORMAL_QQ, "PD202610101430251739");

        assertEquals(ProductOrderStatus.PENDING_PAYMENT.name(), order.getStatus(),
                "⚠️ 归属判错不报任何错，只会让别人的单子被关掉");
        assertTrue(singleMessage().contains("没找到"), singleMessage());
    }

    @Test
    @DisplayName("取消：已支付的商品单回「状态不支持」（与网页端同一条守卫）")
    void cancelOrder_refusesPaidOrder() {
        ProductOrder order = seedProductOrder("PD202610101430251739", NORMAL_USER_ID,
                ProductOrderStatus.PAID.name());

        service.cancelOrder(GROUP_ID, NORMAL_QQ, "PD202610101430251739");

        assertEquals(ProductOrderStatus.PAID.name(), order.getStatus(),
                "已付款的单子不许被取消 —— 那笔钱要退，不是关掉记录就完了");
        assertTrue(singleMessage().contains("不支持取消"), singleMessage());
    }

    // ==================================================================
    // 造数据与桩
    // ==================================================================

    /**
     * 造一个已绑定 QQ 的账号。
     *
     * @param id   用户 ID
     * @param qq   QQ 号
     * @param role 角色
     */
    private void seedUser(Long id, Long qq, UserRole role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setQq(String.valueOf(qq));
        user.setRole(role.name());
        userMapper.seed(user);
    }

    /**
     * 造一件上架商品并放进假表。
     *
     * @param name  名称
     * @param stock 库存
     * @return 带自增 ID 的商品
     */
    private Product seedProduct(String name, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal("3.50"));
        product.setStock(stock);
        product.setEnabled(1);
        product.setSortNo(0);
        return productMapper.seed(product);
    }

    /**
     * 造一张商品购买单并放进假表。
     *
     * @param orderNo 单号
     * @param userId  购买人
     * @param status  状态（{@code ProductOrderStatus} 的枚举名）
     * @return 带自增 ID 的购买单
     */
    private ProductOrder seedProductOrder(String orderNo, Long userId, String status) {
        ProductOrder order = new ProductOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setProductId(1L);
        order.setProductName("可乐");
        order.setUnitPrice(new BigDecimal("3.50"));
        order.setQuantity(2);
        order.setAmount(new BigDecimal("7.00"));
        order.setStatus(status);
        return orderMapper.seed(order);
    }

    /**
     * 造一台在店机台（初始状况良好）。
     *
     * @param id   机台 ID
     * @param name 机台名
     * @return 机台
     */
    private Device seedDevice(long id, String name) {
        Device device = new Device();
        device.setId(id);
        device.setStoreId(STORE_ID);
        device.setName(name);
        device.setTypeId(DEVICE_TYPE_ID);
        device.setStatus("NORMAL");
        device.setSort(10);
        device.setDeleted(0);
        return deviceMapper.seed(device);
    }

    /**
     * 组装一个真的 {@code DeviceService}：假 Mapper + 真 Service。
     *
     * <p>与 {@code DeviceServiceTests} 同一套组装方式 —— 机台查找与状况写入
     * 两段逻辑都被真实执行到，断言才能落在「假表里的状况到底改没改」上。
     * 公告那一层用真的 {@code NoticeService} 配假 Mapper：状况变化会顺带写公告，
     * 那条路走不通的话，这个用例会红在 NPE 上而不是静默跳过。
     *
     * @param mapper 假机台表
     * @return 可用的设备服务
     */
    private static DeviceService newDeviceService(FakeDeviceMapper mapper) {
        FakeEquipmentTypeMapper typeMapper = new FakeEquipmentTypeMapper();
        FakeStoreMapper storeMapper = new FakeStoreMapper();

        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        storeMapper.seed(store);

        EquipmentType type = new EquipmentType();
        type.setId(DEVICE_TYPE_ID);
        type.setCode("PAIPAI");
        type.setName("拍拍机");
        type.setSort(10);
        typeMapper.seed(type);

        return new DeviceService(mapper.asMapper(), typeMapper.asMapper(), storeMapper.asMapper(),
                new NoticeService(new FakeNoticeMapper().asMapper(), event -> { }));
    }

    /**
     * 取这一次调用发出的那一条群消息。
     *
     * <p>顺带断言「恰好一条」—— 它守的是那套「执行必有回响、且只说一次」的纪律：
     * 多一条是刷屏，少一条是让人站在门口等。
     *
     * @return 群消息文本
     */
    private String singleMessage() {
        assertEquals(1, client.groupMessages.size(), "每次调用都该恰好回一条群消息");
        return client.groupMessages.get(0);
    }

    /**
     * 只记下发出的群消息、不碰连接的出站桩（与 {@code QqPaymentProofServiceTests}
     * 里的那个同源）。
     */
    private static class RecordingClient extends OneBotClient {

        private final List<String> groupMessages = new ArrayList<>();

        RecordingClient() {
            // 这个 ObjectMapper 只在真正发送时才用得到，本桩不会走到那里
            super(new ObjectMapper());
        }

        @Override
        public boolean sendGroupMessage(Long groupId, String text) {
            groupMessages.add(text);
            return true;
        }
    }
}
