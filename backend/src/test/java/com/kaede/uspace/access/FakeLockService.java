package com.kaede.uspace.access;

import com.kaede.uspace.lock.LockService;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.LockStatus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内存版的 {@link LockService}，让模块 6 的单元测试不依赖真实门锁。
 *
 * <p>写法与假 Mapper 一致：动态代理 + 按方法名分发，只实现被真正用到的四个方法
 * （{@code listRecords} / {@code queryStatus} / {@code addPasscode} / {@code deletePasscode}）。
 *
 * <p><b>两处必须与真实实现逐字对齐的语义</b>，否则测出来的结论是假的：
 *
 * <ol>
 *   <li><b>区间是闭的</b> {@code [from, to]} —— {@code MockLockServiceImpl#listRecords}
 *       的谓词是 {@code !isBefore(from) && !isAfter(to)}。模块 6 对外用半开区间，
 *       正是靠「上游多给一条、自己过滤掉」来衔接，若这里写成半开就测不出那个过滤逻辑。</li>
 *   <li><b>失败被吞成空列表</b> —— 真实实现在模拟网络异常时
 *       {@code return List.of()} 而非抛异常。{@link #failNextList()} 精确复刻这一点，
 *       用来验证模块 6 能不能把「拉取失败」与「确实没有记录」分开。</li>
 * </ol>
 */
public class FakeLockService implements InvocationHandler {

    /** 模拟云端已有的开门记录 */
    private final List<LockRecordDto> records = new ArrayList<>();

    /** 下一次 {@code listRecords} 是否返回空（模拟「调用失败被吞成空列表」） */
    private boolean nextListFails = false;

    /** 探活返回的锁状态，默认在线 */
    private LockStatus status = LockStatus.ONLINE;

    /** {@code listRecords} 被调用的次数，用于断言「没有白花额度」 */
    private int listCalls = 0;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return LockService 的假实现
     */
    public LockService asService() {
        return (LockService) Proxy.newProxyInstance(
                LockService.class.getClassLoader(),
                new Class<?>[]{LockService.class},
                this);
    }

    /**
     * 预置一条云端已有的开门记录。
     *
     * @param record 开门记录
     * @return 本对象，便于链式调用
     */
    public FakeLockService seedRecord(LockRecordDto record) {
        records.add(record);
        return this;
    }

    /**
     * 让下一次 {@code listRecords} 返回空列表，模拟调用失败。
     *
     * <p>只影响一次，之后恢复正常 —— 这样可以在同一个用例里先验证失败、再验证重试成功。
     *
     * @return 本对象，便于链式调用
     */
    public FakeLockService failNextList() {
        this.nextListFails = true;
        return this;
    }

    /**
     * 设置探活返回的锁状态。
     *
     * @param status 锁状态
     * @return 本对象，便于链式调用
     */
    public FakeLockService withStatus(LockStatus status) {
        this.status = status;
        return this;
    }

    /**
     * 取 {@code listRecords} 的累计调用次数。
     *
     * @return 调用次数
     */
    public int listCalls() {
        return listCalls;
    }

    /**
     * 方法分发。
     *
     * @param proxy  代理对象（未使用）
     * @param method 被调用的方法
     * @param args   调用参数
     * @return 方法返回值
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "listRecords" -> listRecords(args);
            case "queryStatus" -> queryStatus();
            default -> throw new UnsupportedOperationException(
                    "假门锁服务未实现方法 " + method.getName()
                            + " —— 出现这个错误说明模块 6 调用了预期之外的方法，"
                            + "请在 FakeLockService 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 查询开门记录，<b>闭区间</b>。
     *
     * <p>排序容忍 {@code openTime} 为 null 的记录：真实的模拟实现不会产生这种数据
     * （记录由 {@code simulateOpen} 用当前时刻构造），但云端脏数据正是要测的一种情形。
     *
     * @param args 依次为 lockId、from、to
     * @return 开门记录列表，按开门时间升序；模拟失败时返回空列表
     */
    private List<LockRecordDto> listRecords(Object[] args) {
        listCalls++;

        if (nextListFails) {
            nextListFails = false;
            // 逐字复刻真实实现：失败不抛异常，而是返回空列表
            return List.of();
        }

        Long lockId = (Long) args[0];
        LocalDateTime from = (LocalDateTime) args[1];
        LocalDateTime to = (LocalDateTime) args[2];

        return records.stream()
                .filter(r -> lockId.equals(r.lockId()))
                // openTime 为空的记录一律放行，交给 Service 去丢弃并计数。
                // 它本来就无法参与区间比较，若在这里悄悄滤掉，Service 就永远发现不了脏数据 ——
                // 「丢弃了几条」这个数字也就永远是 0，等于这条分支没被测到。
                .filter(r -> r.openTime() == null
                        || (!r.openTime().isBefore(from) && !r.openTime().isAfter(to)))
                // 闭区间：两端都含。与 MockLockServiceImpl 的谓词逐字一致
                .sorted(Comparator.comparing(LockRecordDto::openTime,
                        Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())))
                .toList();
    }

    /**
     * 查询锁状态。
     *
     * @return 由 {@link #withStatus} 设定的状态
     */
    private LockStatus queryStatus() {
        return status;
    }
}
