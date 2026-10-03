package com.kaede.uspace.order;

import com.kaede.uspace.common.config.UploadProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.AdminPayQrVo;
import com.kaede.uspace.order.dto.PayQrSaveRequest;
import com.kaede.uspace.order.dto.PayQrVo;
import com.kaede.uspace.order.entity.PayQr;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayQrService} 的单元测试。
 *
 * <p>三组用例各钉住一类「不报错但会让顾客付不了款」的问题：
 * <ol>
 *   <li><b>两个查询口径不能混</b> —— 收银台只该看到启用中的码（混了，顾客会扫到一张
 *       不再使用的码）；后台必须看到停用的（混了，管理员停用之后就再也找不回它）</li>
 *   <li><b>图片路径必须是本服务上传的收款码图</b> —— 不校验的话，请求体里
 *       可以填外链（顾客浏览器去加载别人的服务器，等于把「谁在什么时候付款」
 *       泄露给第三方），也可以填 {@code /uploads/avatar/xxx.jpg}
 *       把别人的头像当收款码展示出来</li>
 *   <li><b>删除是逻辑删除</b> —— 历史付款凭证上记着 {@code pay_qr_id}，
 *       物理删掉之后那个号就查不出对应的是哪张码了</li>
 * </ol>
 */
class PayQrServiceTests {

    private static final Long STORE_ID = 1L;

    /** 一个合法的图片路径，与上传接口真实返回的形状一致 */
    private static final String VALID_URL = "/uploads/payqr/abc123.png";

    private final FakePayQrMapper payQrMapper = new FakePayQrMapper();
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final UploadProperties uploadProperties = new UploadProperties();

    private PayQrService service;

    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        storeMapper.seed(store);

        service = new PayQrService(payQrMapper.asMapper(), storeMapper.asMapper(), uploadProperties);
    }

    // ==================================================================
    // 查询：两个口径
    // ==================================================================

    @Test
    @DisplayName("收银台：只给启用中的码，停用的不出现")
    void listEnabled_skipsDisabled() {
        payQrMapper.seed(qr("微信收款码", 0, 1));
        payQrMapper.seed(qr("已经不用了的支付宝码", 1, 0));

        List<PayQrVo> result = service.listEnabled();

        assertEquals(1, result.size());
        assertEquals("微信收款码", result.get(0).getName());
        assertEquals("微信", result.get(0).getChannelLabel(),
                "渠道中文名由后端给，前端不必再维护一张映射表");
    }

    @Test
    @DisplayName("后台：含停用的码 —— 否则停用之后就再也找不回它了")
    void listAll_includesDisabled() {
        payQrMapper.seed(qr("微信收款码", 0, 1));
        payQrMapper.seed(qr("已经不用了的支付宝码", 1, 0));

        List<AdminPayQrVo> result = service.listAll();

        assertEquals(2, result.size(),
                "只列启用中的话，管理员停用一张码之后就再也找不回它了，"
                        + "而「临时停用、过阵子再开」正是最常见的用法");
    }

    @Test
    @DisplayName("两张查询都按 sort 升序")
    void list_sortsBySort() {
        payQrMapper.seed(qr("排第二", 20, 1));
        payQrMapper.seed(qr("排第一", 10, 1));

        assertEquals("排第一", service.listEnabled().get(0).getName());
        assertEquals("排第一", service.listAll().get(0).getName());
    }

    @Test
    @DisplayName("没有门店时返回空列表而不是报错")
    void list_returnsEmptyWithoutStore() {
        FakeStoreMapper emptyStore = new FakeStoreMapper();
        PayQrService noStore = new PayQrService(
                payQrMapper.asMapper(), emptyStore.asMapper(), uploadProperties);

        assertTrue(noStore.listEnabled().isEmpty(),
                "空列表是一种真实的运营状态（刚部署完还没配码），前端要能处理它");
        assertTrue(noStore.listAll().isEmpty());
    }

    // ==================================================================
    // 新增
    // ==================================================================

    @Test
    @DisplayName("新增：成功，且落库字段与请求一致")
    void create_success() {
        BizResult<AdminPayQrVo> result = service.create(request("WXPAY", "微信收款码", VALID_URL, 1, 5));

        assertTrue(result.isSuccess());
        assertEquals("微信收款码", result.getData().getName());
        assertEquals("微信", result.getData().getChannelLabel());
        assertEquals(1, result.getData().getEnabled());
        assertEquals(5, result.getData().getSort());

        PayQr saved = payQrMapper.get(result.getData().getId());
        assertEquals(STORE_ID, saved.getStoreId(), "门店 ID 由服务端取当前门店，不由请求体给");
    }

    @Test
    @DisplayName("新增：sort 不传按 0 处理")
    void create_defaultsSortToZero() {
        BizResult<AdminPayQrVo> result = service.create(request("WXPAY", "微信收款码", VALID_URL, 1, null));

        assertTrue(result.isSuccess());
        assertEquals(0, result.getData().getSort());
    }

    @Test
    @DisplayName("新增：渠道取值非法时拒绝")
    void create_rejectsInvalidChannel() {
        BizResult<AdminPayQrVo> result = service.create(request("UNIONPAY", "云闪付", VALID_URL, 1, 0));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.PARAM_INVALID, result.getError());
    }

    @Test
    @DisplayName("新增：外链图片一律拒绝")
    void create_rejectsForeignImageUrl() {
        BizResult<AdminPayQrVo> result = service.create(
                request("WXPAY", "微信收款码", "https://evil.example.com/qr.png", 1, 0));

        assertFalse(result.isSuccess(),
                "不校验的话，顾客的浏览器会去加载别人的服务器 —— "
                        + "等于把「谁在什么时候付款」泄露给第三方");
        assertEquals(ErrorCode.PARAM_INVALID, result.getError());
    }

    @Test
    @DisplayName("新增：别的 kind 的图也拒绝（不能把别人的头像当收款码）")
    void create_rejectsOtherKindImageUrl() {
        BizResult<AdminPayQrVo> result = service.create(
                request("WXPAY", "微信收款码", "/uploads/avatar/kaede_1001.jpg", 1, 0));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.PARAM_INVALID, result.getError());
    }

    @Test
    @DisplayName("新增：带路径穿越的图拒绝")
    void create_rejectsPathTraversal() {
        BizResult<AdminPayQrVo> result = service.create(
                request("WXPAY", "微信收款码", "/uploads/payqr/../avatar/x.png", 1, 0));

        assertFalse(result.isSuccess(),
                "字符串前缀挡不住 ../ —— 与 ImageStorage.deleteByUrl 上那条注释是同一条纪律");
        assertEquals(ErrorCode.PARAM_INVALID, result.getError());
    }

    // ==================================================================
    // 修改与删除
    // ==================================================================

    @Test
    @DisplayName("修改：全量替换，启用状态也在同一条语句里改掉")
    void update_replacesAllFields() {
        PayQr seeded = payQrMapper.seed(qr("微信收款码", 0, 1));

        BizResult<AdminPayQrVo> result = service.update(seeded.getId(),
                request("ALIPAY", "换成支付宝码", VALID_URL, 0, 9));

        assertTrue(result.isSuccess());
        PayQr updated = payQrMapper.get(seeded.getId());
        assertEquals("ALIPAY", updated.getChannel());
        assertEquals("换成支付宝码", updated.getName());
        assertEquals(0, updated.getEnabled(), "启停是这张码编辑表单的一部分，不必单开一个接口");
        assertEquals(9, updated.getSort());
    }

    @Test
    @DisplayName("修改：记录不存在时返回 404 码")
    void update_notFound() {
        BizResult<AdminPayQrVo> result = service.update(999L, request("WXPAY", "微信收款码", VALID_URL, 1, 0));

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.PAY_QR_NOT_FOUND, result.getError());
    }

    @Test
    @DisplayName("删除：逻辑删除，行还在（历史凭证上的 pay_qr_id 还查得到）")
    void delete_isLogical() {
        PayQr seeded = payQrMapper.seed(qr("微信收款码", 0, 1));

        BizResult<Void> result = service.delete(seeded.getId());

        assertTrue(result.isSuccess());
        assertNull(service.listAll().stream()
                        .filter(vo -> vo.getId().equals(seeded.getId()))
                        .findFirst()
                        .orElse(null),
                "删掉之后不该再出现在任何列表里");
        assertEquals(1, payQrMapper.get(seeded.getId()).getDeleted(),
                "但行还在 —— 历史凭证上记着 pay_qr_id，物理删掉就说不清那笔钱扫的是哪张码了");
    }

    @Test
    @DisplayName("删除：记录不存在时返回 404 码")
    void delete_notFound() {
        BizResult<Void> result = service.delete(999L);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.PAY_QR_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 造一条收款码。
     *
     * @param name    显示名
     * @param sort    排序值
     * @param enabled 是否启用（1/0）
     * @return 收款码实体，ID 由假 Mapper 分配
     */
    private static PayQr qr(String name, int sort, int enabled) {
        PayQr qr = new PayQr();
        qr.setStoreId(STORE_ID);
        qr.setChannel("WXPAY");
        qr.setName(name);
        qr.setImageUrl(VALID_URL);
        qr.setEnabled(enabled);
        qr.setSort(sort);
        return qr;
    }

    /**
     * 造一个新增 / 修改的请求体。
     *
     * @param channel  渠道
     * @param name     显示名
     * @param imageUrl 图片路径
     * @param enabled  是否启用
     * @param sort     排序值，可为 null
     * @return 请求体
     */
    private static PayQrSaveRequest request(String channel, String name,
                                            String imageUrl, int enabled, Integer sort) {
        PayQrSaveRequest request = new PayQrSaveRequest();
        request.setChannel(channel);
        request.setName(name);
        request.setImageUrl(imageUrl);
        request.setEnabled(enabled);
        request.setSort(sort);
        return request;
    }
}
