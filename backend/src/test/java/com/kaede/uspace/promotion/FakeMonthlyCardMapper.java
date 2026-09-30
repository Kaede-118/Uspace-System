package com.kaede.uspace.promotion;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.promotion.mapper.MonthlyCardMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存版的 {@link MonthlyCardMapper}，让月卡服务的单元测试不依赖数据库。
 *
 * <p><b>假实现必须与真 SQL 的语义逐条对齐</b>，否则单测通过也不可信。
 * 本类重点复刻的是 {@code selectActiveAt} 的四个条件 ——
 * 它们每一条都挡着一类静默的错账（漏判状态会让已退款的卡继续免单、
 * 漏判日期会让过期卡继续免单），所以假实现里也要一条不落地写上。
 *
 * <p>用动态代理而不是手写实现类：{@code BaseMapper} 上有三十多个方法，
 * 而 {@code MonthlyCardService} 只用其中几个。遇到没实现的方法直接抛异常
 * 并指出该补哪个 —— 这反而是一层保护，Service 一旦用了预期之外的方法，
 * 测试会立刻失败。
 *
 * <p><b>不模拟的东西</b>：SQL 是否正确、列名映射是否对得上、审计字段能否
 * 真的自动填充 —— 那些只有连真库才能验证，由
 * {@code MonthlyCardMapperIntegrationTests} 负责。
 */
public class FakeMonthlyCardMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, MonthlyCard> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return MonthlyCardMapper 的假实现
     */
    public MonthlyCardMapper asMapper() {
        return (MonthlyCardMapper) Proxy.newProxyInstance(
                MonthlyCardMapper.class.getClassLoader(),
                new Class<?>[]{MonthlyCardMapper.class},
                this);
    }

    /**
     * 预置一张月卡，模拟「库里已经有这张卡」。
     *
     * @param card 月卡，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public MonthlyCard seed(MonthlyCard card) {
        if (card.getId() == null) {
            card.setId(nextId++);
        }
        if (card.getDeleted() == null) {
            card.setDeleted(0);
        }
        rows.put(card.getId(), card);
        return card;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * <p>取出的是同一个对象引用，因此测试看到的就是 Service 改过之后的样子。
     *
     * @param id 月卡 ID
     * @return 月卡；不存在时返回 null
     */
    public MonthlyCard get(Long id) {
        return rows.get(id);
    }

    /**
     * 表中当前的卡数，供测试断言「有没有多出记录」。
     *
     * @return 记录数
     */
    public int size() {
        return rows.size();
    }

    /**
     * 方法分发。方法名唯一，所以按名字匹配即可。
     *
     * @param proxy  代理对象（未使用）
     * @param method 被调用的方法
     * @param args   调用参数
     * @return 方法返回值
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "insert" -> insert((MonthlyCard) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectActiveAt" -> selectActiveAt(args);
            case "selectByUser" -> selectByUser((Long) args[0]);
            case "expireBefore" -> expireBefore((LocalDate) args[0]);
            case "selectPageBy" -> selectPageBy(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeMonthlyCardMapper 中补上对应实现");
        };
    }

    // ------------------------------------------------------------------
    // 以下是各方法的内存实现
    // ------------------------------------------------------------------

    /**
     * 插入月卡，模拟自增主键的分配。
     *
     * @param card 待插入的月卡
     * @return 受影响行数，恒为 1
     */
    private int insert(MonthlyCard card) {
        if (card.getId() == null) {
            card.setId(nextId++);
        }
        if (card.getDeleted() == null) {
            card.setDeleted(0);
        }
        rows.put(card.getId(), card);
        return 1;
    }

    /**
     * 按 ID 查未删除的卡。
     *
     * @param id 月卡 ID
     * @return 月卡；不存在或已删除时返回 null
     */
    private MonthlyCard selectById(Long id) {
        MonthlyCard card = rows.get(id);
        return isAlive(card) ? card : null;
    }

    /**
     * 查某日生效的卡。
     *
     * <p>四个条件与真 SQL 逐字对应：逻辑未删、状态为生效中、
     * 生效日不晚于该日、失效日不早于该日。取 ID 最大的一张，
     * 对应真 SQL 的 {@code ORDER BY id DESC LIMIT 1}。
     *
     * @param args 依次为用户 ID、日期
     * @return 生效中的月卡；没有则返回 null
     */
    private MonthlyCard selectActiveAt(Object[] args) {
        Long userId = (Long) args[0];
        LocalDate date = (LocalDate) args[1];

        return rows.values().stream()
                .filter(FakeMonthlyCardMapper::isAlive)
                .filter(c -> Objects.equals(c.getUserId(), userId))
                .filter(c -> MonthlyCardStatus.ACTIVE.name().equals(c.getStatus()))
                .filter(c -> c.getStartDate() != null && !c.getStartDate().isAfter(date))
                .filter(c -> c.getEndDate() != null && !c.getEndDate().isBefore(date))
                .max(Comparator.comparing(MonthlyCard::getId))
                .orElse(null);
    }

    /**
     * 查某用户的全部卡，按 ID 倒序。
     *
     * @param userId 用户 ID
     * @return 月卡列表
     */
    private List<MonthlyCard> selectByUser(Long userId) {
        return rows.values().stream()
                .filter(FakeMonthlyCardMapper::isAlive)
                .filter(c -> Objects.equals(c.getUserId(), userId))
                .sorted(Comparator.comparing(MonthlyCard::getId).reversed())
                .toList();
    }

    /**
     * 把已过有效期的卡置为已过期。
     *
     * <p>边界与真 SQL 一致：{@code end_date < 今天} 才翻转，
     * {@code end_date = 今天} 仍算有效。
     *
     * @param today 今天的日期
     * @return 受影响行数
     */
    private int expireBefore(LocalDate today) {
        int affected = 0;
        for (MonthlyCard card : rows.values()) {
            if (isAlive(card)
                    && MonthlyCardStatus.ACTIVE.name().equals(card.getStatus())
                    && card.getEndDate() != null
                    && card.getEndDate().isBefore(today)) {
                card.setStatus(MonthlyCardStatus.EXPIRED.name());
                affected++;
            }
        }
        return affected;
    }

    /**
     * 按条件筛选后填充分页结果。
     *
     * <p>不做真的分页（测试只关心筛选条件对不对），但 total 与 records 都按
     * 真实语义给出，免得调用方拿到的结构自相矛盾。
     *
     * @param args 依次为分页参数、用户 ID、状态、卡类型
     * @return 填好的分页结果
     */
    @SuppressWarnings("unchecked")
    private IPage<MonthlyCard> selectPageBy(Object[] args) {
        IPage<MonthlyCard> page = (IPage<MonthlyCard>) args[0];
        Long userId = (Long) args[1];
        String status = (String) args[2];
        String cardType = (String) args[3];

        List<MonthlyCard> matched = rows.values().stream()
                .filter(FakeMonthlyCardMapper::isAlive)
                .filter(c -> userId == null || Objects.equals(c.getUserId(), userId))
                .filter(c -> isBlank(status) || status.equals(c.getStatus()))
                .filter(c -> isBlank(cardType) || cardType.equals(c.getCardType()))
                .sorted(Comparator.comparing(MonthlyCard::getId).reversed())
                .toList();

        page.setRecords(matched);
        page.setTotal(matched.size());
        return page;
    }

    /**
     * 判断记录是否存在且未被逻辑删除。
     *
     * <p>真库的每条手写 SQL 都带 {@code deleted = 0}，假实现照做 ——
     * 少了这一条，测试就发现不了「SQL 漏写 deleted」这类问题。
     *
     * @param card 月卡，可为 null
     * @return 有效返回 true
     */
    private static boolean isAlive(MonthlyCard card) {
        return card != null && !Integer.valueOf(1).equals(card.getDeleted());
    }

    /**
     * 判断字符串为空。
     *
     * @param value 待判断的字符串，可为 null
     * @return 为 null 或空串时返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
