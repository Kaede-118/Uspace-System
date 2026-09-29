package com.kaede.uspace.lock.mapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 内存版的 {@link LockMapper}，让需要「当前门锁 ID」的单元测试不依赖数据库。
 *
 * <p>本接口只有一个业务方法，本来直接实现即可 —— 但它继承自
 * {@code BaseMapper}，那上面有三十多个方法，手写实现要为一堆用不到的方法
 * 写空壳。所以仍用动态代理，只处理真正被调用的那一个。
 *
 * <p><b>放在 {@code lock} 包的测试目录下</b>而不是调用方（模块 8）那边：
 * 它模拟的是 {@code LockMapper}，语义上属于这里，与
 * {@code FakeSysUserMapper} 放在 {@code user} 包是同一个道理。
 */
public class FakeLockMapper implements InvocationHandler {

    /** 当前门锁 ID。默认给一个有效值，多数用例不关心它的存在 */
    private Long currentId = 1L;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return LockMapper 的假实现
     */
    public LockMapper asMapper() {
        return (LockMapper) Proxy.newProxyInstance(
                LockMapper.class.getClassLoader(),
                new Class<?>[]{LockMapper.class},
                this);
    }

    /**
     * 设定当前门锁 ID。
     *
     * @param lockId 门锁 ID
     * @return 本对象，便于链式调用
     */
    public FakeLockMapper withLock(Long lockId) {
        this.currentId = lockId;
        return this;
    }

    /**
     * 模拟「门店还没配置门锁」—— 下单链路应当据此拒绝，而不是发出一串没有锁可开的密码。
     *
     * @return 本对象，便于链式调用
     */
    public FakeLockMapper withoutLock() {
        this.currentId = null;
        return this;
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
            case "selectCurrentId" -> currentId;
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeLockMapper 中补上对应实现");
        };
    }
}
