package com.kaede.uspace.lock.mock;

import com.kaede.uspace.lock.LockService;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.dto.GetOneTimePasscodeRequest;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.LockStatus;
import com.kaede.uspace.lock.dto.OneTimePasscodeResult;
import com.kaede.uspace.lock.dto.PasscodeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门锁模拟实现的测试。
 *
 * <p>覆盖两类内容：
 * <ol>
 *   <li><b>正常链路</b> —— 下发密码 → 开门 → 查记录 → 删除密码</li>
 *   <li><b>真实约束</b> —— 通通锁「自定义密码只能设限时密码」这条硬约束，
 *       确保模拟实现不会比真实接口宽松，否则切到真实实现时才暴露问题</li>
 * </ol>
 *
 * <p>注入的是 {@link LockService} 接口而非实现类，这样将来换成
 * {@code TtlockServiceImpl} 时本测试依然适用。
 */
@SpringBootTest
class MockLockServiceImplTests {

    /** 注入接口，验证 provider 配置确实把 mock 实现装配了进来 */
    @Autowired
    private LockService lockService;

    /** 仅在测试中用于制造开门记录 —— 该方法是模拟实现专有的，不在接口上 */
    @Autowired
    private MockLockServiceImpl mockLockService;

    /**
     * 构造一个合法的下发请求。
     *
     * @param lockId 锁 ID
     * @return 有效期 3 小时、addType=2（远程下发）的请求
     */
    private AddPasscodeRequest validRequest(Long lockId) {
        AddPasscodeRequest request = new AddPasscodeRequest();
        request.setLockId(lockId);
        request.setKeyboardPwdName("测试订单");
        request.setStartTime(LocalDateTime.now());
        request.setEndTime(LocalDateTime.now().plusHours(3));
        return request;
    }

    /** 返回当前时间前后各一分钟的区间，用于查询刚产生的记录 */
    private LocalDateTime[] recentWindow() {
        return new LocalDateTime[]{
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusMinutes(1)
        };
    }

    @Test
    @DisplayName("下发限时密码：成功，并返回 6 位数字密码")
    void addPasscode_success() {
        PasscodeResult result = lockService.addPasscode(validRequest(1001L));

        assertTrue(result.isSuccess(), "下发应当成功");
        assertEquals(0, result.getErrcode(), "成功时 errcode 应为 0");
        assertNotNull(result.getKeyboardPwd(), "成功时应返回密码");
        assertEquals(6, result.getKeyboardPwd().length(), "通通锁键盘密码为 6 位数字");
    }

    @Test
    @DisplayName("下发密码：未给有效期时被拒绝（自定义密码只能是限时密码）")
    void addPasscode_rejectedWhenValidityMissing() {
        AddPasscodeRequest request = validRequest(1002L);
        request.setEndTime(null);

        PasscodeResult result = lockService.addPasscode(request);

        assertFalse(result.isSuccess(), "缺少有效期应当被拒绝");
        assertNull(result.getKeyboardPwd(), "失败时不应返回密码");
        assertNotNull(result.getErrmsg(), "失败时应给出原因，便于排查");
    }

    @Test
    @DisplayName("下发密码：失效时间早于生效时间时被拒绝")
    void addPasscode_rejectedWhenEndBeforeStart() {
        AddPasscodeRequest request = validRequest(1003L);
        request.setStartTime(LocalDateTime.now());
        request.setEndTime(LocalDateTime.now().minusHours(1));

        assertFalse(lockService.addPasscode(request).isSuccess());
    }

    @Test
    @DisplayName("完整链路：下发密码 → 开门 → 查出记录 → 删除密码")
    void fullFlow() {
        Long lockId = 1004L;
        Long userId = 2001L;

        PasscodeResult issued = lockService.addPasscode(validRequest(lockId));
        assertTrue(issued.isSuccess());
        String keyboardPwd = issued.getKeyboardPwd();

        // 模拟用户到场开门
        mockLockService.simulateOpen(lockId, keyboardPwd, userId);

        LocalDateTime[] window = recentWindow();
        List<LockRecordDto> records = lockService.listRecords(lockId, window[0], window[1]);
        assertEquals(1, records.size(), "应当查到 1 条开门记录");
        assertEquals(keyboardPwd, records.get(0).keyboardPwd(), "记录应关联到所用密码");
        assertEquals(userId, records.get(0).userId(), "记录应能反查出发起人");

        // 订单结束后删除密码
        assertTrue(lockService.deletePasscode(lockId, keyboardPwd).isSuccess());

        // 密码失效不应抹掉历史记录 —— 开门记录要留给计费与审计
        assertFalse(lockService.listRecords(lockId, window[0], window[1]).isEmpty(),
                "删除密码后开门记录仍应保留");
    }

    @Test
    @DisplayName("删除密码：重复删除同一密码仍返回成功（幂等）")
    void deletePasscode_isIdempotent() {
        Long lockId = 1007L;
        String keyboardPwd = lockService.addPasscode(validRequest(lockId)).getKeyboardPwd();

        assertTrue(lockService.deletePasscode(lockId, keyboardPwd).isSuccess());
        assertTrue(lockService.deletePasscode(lockId, keyboardPwd).isSuccess(),
                "重复删除不应报错，避免订单重试时失败");
    }

    @Test
    @DisplayName("查询锁状态：模拟实现返回在线")
    void queryStatus_returnsOnline() {
        assertEquals(LockStatus.ONLINE, lockService.queryStatus(1005L));
    }

    @Test
    @DisplayName("模拟开门：密码不存在时抛异常，避免演示出假数据")
    void simulateOpen_throwsWhenPasscodeUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> mockLockService.simulateOpen(1006L, "000000", null));
    }

    // ==================================================================
    // 一次性密码（模块 11 的 fw开门 用）
    // ==================================================================

    /**
     * 构造一个合法的一次性密码请求。
     *
     * @param lockId    锁 ID
     * @param startTime 生效时刻
     * @return 请求
     */
    private GetOneTimePasscodeRequest oneTimeRequest(Long lockId, LocalDateTime startTime) {
        GetOneTimePasscodeRequest request = new GetOneTimePasscodeRequest();
        request.setLockId(lockId);
        request.setKeyboardPwdName("群指令-测试");
        request.setStartTime(startTime);
        return request;
    }

    @Test
    @DisplayName("一次性密码：取到 6 位数字与锁云侧 ID")
    void getOneTimePasscode_success() {
        OneTimePasscodeResult result =
                lockService.getOneTimePasscode(oneTimeRequest(2001L, LocalDateTime.now()));

        assertTrue(result.isSuccess(), "取密码应当成功");
        assertNotNull(result.getKeyboardPwd());
        assertEquals(6, result.getKeyboardPwd().length(), "通通锁的键盘密码是 6 位数字");
        assertNotNull(result.getKeyboardPwdId(), "一次性密码要带回锁云侧 ID");
        assertNotNull(result.getEndTime(), "有效期窗口要一并带回来");
    }

    @Test
    @DisplayName("一次性密码：⚠️ 用一次即焚，第二次开不了门")
    void getOneTimePasscode_burnsAfterUse() {
        Long lockId = 2002L;
        String keyboardPwd = lockService.getOneTimePasscode(
                oneTimeRequest(lockId, LocalDateTime.now())).getKeyboardPwd();

        mockLockService.simulateOpen(lockId, keyboardPwd, null);
        assertThrows(IllegalArgumentException.class,
                () -> mockLockService.simulateOpen(lockId, keyboardPwd, null),
                "「单次密码」的语义就是只能用一次 —— 这一条不成立的话，"
                        + "群里发出去的那串在 6 小时内可以被任意多人使用");
    }

    @Test
    @DisplayName("一次性密码：窗口外失效（通通锁规则是生效起 6 小时）")
    void getOneTimePasscode_expiresAfterWindow() {
        Long lockId = 2003L;
        // 生效时刻设在 7 小时前 → 6 小时窗口已经过去
        String keyboardPwd = lockService.getOneTimePasscode(
                oneTimeRequest(lockId, LocalDateTime.now().minusHours(7))).getKeyboardPwd();

        assertThrows(IllegalArgumentException.class,
                () -> mockLockService.simulateOpen(lockId, keyboardPwd, null));
    }

    @Test
    @DisplayName("模拟开门：⚠️ 超期的限时密码也开不了门")
    void simulateOpen_rejectsExpiredPasscode() {
        Long lockId = 2004L;
        AddPasscodeRequest request = new AddPasscodeRequest();
        request.setLockId(lockId);
        request.setStartTime(LocalDateTime.now().minusHours(4));
        request.setEndTime(LocalDateTime.now().minusHours(1));
        String keyboardPwd = lockService.addPasscode(request).getKeyboardPwd();

        assertThrows(IllegalArgumentException.class,
                () -> mockLockService.simulateOpen(lockId, keyboardPwd, null),
                "模拟实现不该比真锁宽松 —— 真锁在窗口外会直接拒绝这串密码");
    }

    @Test
    @DisplayName("一次性密码与限时密码互不干扰")
    void oneTimeAndTimedPasscodesCoexist() {
        Long lockId = 2005L;
        String timed = lockService.addPasscode(validRequest(lockId)).getKeyboardPwd();
        String oneTime = lockService.getOneTimePasscode(
                oneTimeRequest(lockId, LocalDateTime.now())).getKeyboardPwd();

        assertNotEquals(timed, oneTime, "两串密码不该相同（生成时会查重，撞号要重摇）");

        // 用掉一次性的那串，限时的那串仍然能开门 ——
        // 这正是「群里的快路径失效了还有网页端的兜底密码」那条设计的技术基础
        mockLockService.simulateOpen(lockId, oneTime, null);
        mockLockService.simulateOpen(lockId, timed, null);
    }
}
