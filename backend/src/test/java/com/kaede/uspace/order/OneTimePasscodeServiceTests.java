package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.OneTimePasscodeVo;
import com.kaede.uspace.order.entity.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OneTimePasscodeService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>数据访问由 {@link FakeOrderMapper} 顶替，
 * 门锁由 {@link FakeLockService} 顶替 —— 后者带<b>调用计数</b>，
 * 而计数正是本类要钉住的两条策略的锚点：
 * <ol>
 *   <li><b>每次发放都调云取新的</b>（不做复用）—— 计数会随调用次数线性增长</li>
 *   <li><b>被拒绝的请求一次额度都不消耗</b> —— 计数为零</li>
 * </ol>
 */
class OneTimePasscodeServiceTests {

    private FakeOrderMapper orderMapper;

    private FakeLockService lockService;

    private OneTimePasscodeService service;

    @BeforeEach
    void setUp() {
        orderMapper = new FakeOrderMapper();
        lockService = new FakeLockService();
        service = new OneTimePasscodeService(orderMapper.asMapper(), lockService);
    }

    /**
     * 造一张进行中的订单。
     *
     * @param userId 下单人
     * @param lockId 门锁 ID，可为 null（门店没配锁的情形）
     * @return 订单实体
     */
    private Order inUseOrder(Long userId, Long lockId) {
        Order order = new Order();
        order.setOrderNo("OD20261004000001");
        order.setUserId(userId);
        order.setStoreId(1L);
        order.setLockId(lockId);
        order.setStatus(OrderStatus.IN_USE.name());
        order.setStartTime(LocalDateTime.now());
        return orderMapper.seed(order);
    }

    @Test
    @DisplayName("发放：取到密码并记进订单（结算时才知道该撤哪一串）")
    void issue_storesPasscodeOnOrder() {
        Order order = inUseOrder(9L, 100L);

        BizResult<OneTimePasscodeVo> result = service.issue(9L, order.getId());

        assertTrue(result.isSuccess(), result.resolveMessage());
        assertEquals("654321", result.getData().getPasscode());
        assertEquals("654321", order.getOneTimePasscode(),
                "要落在订单上 —— 结算时靠它撤销，排障时也靠它");
        assertNotNull(order.getOneTimePasscodeEnd());
        assertEquals(1, lockService.oneTimeCalls());
    }

    @Test
    @DisplayName("发放：⚠️ 每次都调云取新的，不做复用")
    void issue_alwaysCallsLockCloud() {
        Order order = inUseOrder(9L, 100L);

        service.issue(9L, order.getId());
        service.issue(9L, order.getId());

        assertEquals(2, lockService.oneTimeCalls(),
                "「复用未消费的那一串」的方案已废弃：判断旧串还在不在同样要花一次门锁云调用，"
                        + "成本与直接生成相同，却多担一份「把死码发给站在门口的人」的风险");
    }

    @Test
    @DisplayName("发放：门锁云失败时返回错误码，且【不落库】")
    void issue_doesNotPersistWhenLockCloudFails() {
        Order order = inUseOrder(9L, 100L);
        lockService.failNextOneTime();

        BizResult<OneTimePasscodeVo> result = service.issue(9L, order.getId());

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.LOCK_CLOUD_UNAVAILABLE, result.getError());
        assertNull(order.getOneTimePasscode(),
                "取不到密码就不该在订单上留下痕迹 —— 留了的话结算时会去撤一串不存在的密码");
    }

    @Test
    @DisplayName("发放：不是本人的订单一律当作「不存在」")
    void issue_rejectsOthersOrder() {
        Order order = inUseOrder(9L, 100L);

        BizResult<OneTimePasscodeVo> result = service.issue(8L, order.getId());

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.ORDER_NOT_FOUND, result.getError(),
                "与订单模块同一条口径：防的是拿别人的订单号来试");
        assertEquals(0, lockService.oneTimeCalls(), "被拒绝的请求不能消耗门锁云额度");
    }

    @Test
    @DisplayName("发放：订单已结束时拒绝")
    void issue_rejectsSettledOrder() {
        Order order = new Order();
        order.setOrderNo("OD20261004000002");
        order.setUserId(9L);
        order.setStoreId(1L);
        order.setLockId(100L);
        order.setStatus(OrderStatus.PENDING_PAYMENT.name());
        orderMapper.seed(order);

        BizResult<OneTimePasscodeVo> result = service.issue(9L, order.getId());

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError());
        assertNull(order.getOneTimePasscode());
        assertEquals(0, lockService.oneTimeCalls());
    }

    @Test
    @DisplayName("发放：门店没配门锁时拒绝")
    void issue_rejectsWhenLockMissing() {
        Order order = inUseOrder(9L, null);

        BizResult<OneTimePasscodeVo> result = service.issue(9L, order.getId());

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.LOCK_NOT_CONFIGURED, result.getError());
        assertEquals(0, lockService.oneTimeCalls());
    }

    @Test
    @DisplayName("发放：订单不存在时也是 ORDER_NOT_FOUND")
    void issue_rejectsMissingOrder() {
        BizResult<OneTimePasscodeVo> result = service.issue(9L, 404L);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.ORDER_NOT_FOUND, result.getError());
    }
}
