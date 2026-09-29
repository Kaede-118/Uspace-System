package com.kaede.uspace.order;

import com.kaede.uspace.lock.LockService;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.LockStatus;
import com.kaede.uspace.lock.dto.PasscodeResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内存版的 {@link LockService}，供模块 8 的单元测试使用。
 *
 * <p><b>为什么不复用 {@code access} 包里已有的那个</b>：那个是为了模块 6
 * （只读地拉开门记录）而写的，它只实现了 {@code listRecords} 与 {@code queryStatus}，
 * 遇到 {@code addPasscode} 会直接抛「未实现」。而模块 8 恰恰相反 ——
 * 它的核心是<b>下发、续期、撤销密码</b>，那三个方法才是被测的重点。
 *
 * <p>与既有假实现不同，这里<b>直接实现接口而不是用动态代理</b>：
 * 接口只有五个方法，全部实现一遍反而更短，而且漏实现会在编译期就被发现，
 * 不必等到运行期才抛「未实现方法」。同时它带着<b>调用计数</b> ——
 * 门锁云额度是 30,000 次/月的硬约束，测试要能断言
 * 「被拒绝的下单请求一次额度都没消耗」。
 */
public class FakeLockService implements LockService {

    /** 已下发的密码，key 为 {@code lockId:passcode} */
    private final Set<String> issued = new HashSet<>();

    /** 下一次 addPasscode 是否失败 */
    private boolean nextAddFails = false;

    /** changePasscode 是否一律失败（模拟密码已不存在，如进程重启后的内存实现） */
    private boolean changesAlwaysFail = false;

    /** 下发密码的固定返回值，便于断言。可真机生成的是随机 6 位数字 */
    private String passcode = "123456";

    private int addCalls = 0;
    private int changeCalls = 0;
    private int deleteCalls = 0;

    /** 最后一次 addPasscode 收到的请求，供断言「传给锁的窗口参数对不对」 */
    private AddPasscodeRequest lastAddRequest;

    /** 最后一次 changePasscode 收到的时间窗口 */
    private LocalDateTime lastChangeStart;
    private LocalDateTime lastChangeEnd;

    /**
     * 让下一次 {@code addPasscode} 失败，模拟门锁云调用异常。
     *
     * <p>只影响一次，之后恢复正常 —— 这样一个用例里可以先验证失败、再验证重试。
     *
     * @return 本对象，便于链式调用
     */
    public FakeLockService failNextAdd() {
        this.nextAddFails = true;
        return this;
    }

    /**
     * 让所有 {@code changePasscode} 调用失败。
     *
     * <p>模拟「密码在锁上已不存在」—— 真实场景下这是网关故障，
     * 而模拟实现里进程重启就会造成这个状态。模块 8 据此降级为重新下发。
     *
     * @return 本对象，便于链式调用
     */
    public FakeLockService failAllChanges() {
        this.changesAlwaysFail = true;
        return this;
    }

    /**
     * 设定 {@code addPasscode} 返回的密码。
     *
     * @param passcode 密码
     * @return 本对象，便于链式调用
     */
    public FakeLockService withPasscode(String passcode) {
        this.passcode = passcode;
        return this;
    }

    /** @return {@code addPasscode} 的累计调用次数 */
    public int addCalls() {
        return addCalls;
    }

    /** @return {@code changePasscode} 的累计调用次数 */
    public int changeCalls() {
        return changeCalls;
    }

    /** @return {@code deletePasscode} 的累计调用次数 */
    public int deleteCalls() {
        return deleteCalls;
    }

    /** @return 最后一次 {@code addPasscode} 的请求；从未调用过时为 null */
    public AddPasscodeRequest lastAddRequest() {
        return lastAddRequest;
    }

    /** @return 最后一次 {@code changePasscode} 的生效时间 */
    public LocalDateTime lastChangeStart() {
        return lastChangeStart;
    }

    /** @return 最后一次 {@code changePasscode} 的失效时间 */
    public LocalDateTime lastChangeEnd() {
        return lastChangeEnd;
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：校验真实约束（必须给有效期、失效晚于生效），记下调用次数。
     */
    @Override
    public PasscodeResult addPasscode(AddPasscodeRequest request) {
        addCalls++;
        lastAddRequest = request;

        if (nextAddFails) {
            nextAddFails = false;
            return PasscodeResult.fail(-1, "模拟网络异常：下发密码失败");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return PasscodeResult.fail(-1, "通通锁自定义密码只支持限时密码，必须给出生效与失效时间");
        }
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            return PasscodeResult.fail(-1, "失效时间必须晚于生效时间");
        }

        issued.add(key(request.getLockId(), passcode));
        return PasscodeResult.ok(passcode);
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：只改有效期窗口，密码本身不变 —— 与真实的
     * {@code MockLockServiceImpl#changePasscode} 语义一致，
     * 模块 8 的「续期不换密码」正是建立在这条语义上。
     *
     * <p><b>默认成功</b>，不校验「这个密码是不是本假实现下发过的」：
     * 测试里的订单多半是直接预置的，密码从未经过 {@code addPasscode}，
     * 若在这里卡一道，主路径永远走不到，反而测不出续期逻辑本身。
     * 要测「密码已不存在」这个失败分支，用 {@link #failAllChanges()}。
     */
    @Override
    public PasscodeResult changePasscode(Long lockId, String keyboardPwd,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        changeCalls++;
        lastChangeStart = startTime;
        lastChangeEnd = endTime;

        if (changesAlwaysFail) {
            return PasscodeResult.fail(-1, "密码不存在：lockId=" + lockId);
        }
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return PasscodeResult.fail(-1, "失效时间必须晚于生效时间");
        }
        return PasscodeResult.ok(keyboardPwd);
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：幂等 —— 密码不存在也返回成功。
     */
    @Override
    public PasscodeResult deletePasscode(Long lockId, String keyboardPwd) {
        deleteCalls++;
        issued.remove(key(lockId, keyboardPwd));
        return PasscodeResult.ok(keyboardPwd);
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：固定返回在线。模块 8 的单元测试不涉及锁状态判断，
     * 保留实现只是为了让接口完整。
     */
    @Override
    public LockStatus queryStatus(Long lockId) {
        return LockStatus.ONLINE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：返回空列表。开门记录的拉取属于模块 6，模块 8 不碰。
     */
    @Override
    public List<LockRecordDto> listRecords(Long lockId, LocalDateTime from, LocalDateTime to) {
        return new ArrayList<>();
    }

    /**
     * 拼装内存缓存的 key。
     *
     * @param lockId      锁 ID
     * @param keyboardPwd 密码
     * @return 形如 {@code 1001:123456} 的字符串
     */
    private static String key(Long lockId, String keyboardPwd) {
        return lockId + ":" + keyboardPwd;
    }
}
