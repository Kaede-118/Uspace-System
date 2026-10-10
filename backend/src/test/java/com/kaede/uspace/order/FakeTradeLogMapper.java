package com.kaede.uspace.order;

import com.kaede.uspace.order.entity.TradeLog;
import com.kaede.uspace.order.mapper.TradeLogMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存版的 {@link TradeLogMapper}，让交易流水的相关测试不依赖数据库。
 *
 * <p><b>它会模拟审计字段的自动填充</b>（真表由 {@code AuditMetaObjectHandler}
 * 填 {@code created_at}）：假实现若不填，用例断言「这条流水记在什么时候」
 * 就只能对着 null 说话 —— 与 {@code FakeProductOrderMapper} 同一套做法。
 *
 * <p>只实现 {@code insert} 一个方法：流水是 append-only 的留档，
 * 当前没有任何读它的代码路径。将来加了「流水查询」再来补，
 * 届时那条路径也会有自己的用例。
 */
public class FakeTradeLogMapper implements InvocationHandler {

    /** 模拟数据表，按写入顺序排列（流水本来就是按时间读的） */
    private final List<TradeLog> rows = new ArrayList<>();

    /**
     * 生成接口代理，交给服务使用。
     *
     * @return TradeLogMapper 的假实现
     */
    public TradeLogMapper asMapper() {
        return (TradeLogMapper) Proxy.newProxyInstance(
                TradeLogMapper.class.getClassLoader(),
                new Class<?>[]{TradeLogMapper.class},
                this);
    }

    /**
     * 取当前的全部流水，供测试断言。
     *
     * @return 流水列表（内部对象本身，测试可以直接读字段）
     */
    public List<TradeLog> rows() {
        return rows;
    }

    /**
     * 表里当前的流水条数，供「有没有多记 / 漏记」的断言使用。
     *
     * @return 条数
     */
    public int size() {
        return rows.size();
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
            case "insert" -> insert((TradeLog) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明有代码去读流水表了，"
                            + "请在 FakeTradeLogMapper 中补上对应实现");
        };
    }

    /**
     * 插入一条流水，模拟自增主键与 {@code created_at} 的自动填充。
     *
     * @param entry 待插入的流水
     * @return 受影响行数，恒为 1
     */
    private int insert(TradeLog entry) {
        if (entry.getId() == null) {
            entry.setId((long) rows.size() + 1);
        }
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        }
        rows.add(entry);
        return 1;
    }
}
