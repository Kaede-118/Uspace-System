package com.kaede.uspace.product;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.product.mapper.ProductMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link ProductMapper}，让商品服务的单元测试不依赖数据库。
 *
 * <p><b>会模拟审计字段的自动填充</b>：真实的 MyBatis-Plus 在 insert 时会把
 * {@code createdAt} / {@code updatedAt} 填上，而这两列在建表脚本里是
 * {@code NOT NULL} 且无默认值 —— 假实现若不填，测试里造出来的数据
 * 就是真实环境下永远造不出来的形态。
 *
 * <p><b>{@code deductStock} 忠实照搬真 SQL 的条件语义</b>
 * （{@code WHERE id = ? AND stock >= ? AND deleted = 0}）：
 * 少了库存条件，「卖光之后扣减失败」这条分支就测不出来，
 * 而那正是本次库存方案唯一的硬防线。
 */
public class FakeProductMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, Product> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return ProductMapper 的假实现
     */
    public ProductMapper asMapper() {
        return (ProductMapper) Proxy.newProxyInstance(
                ProductMapper.class.getClassLoader(),
                new Class<?>[]{ProductMapper.class},
                this);
    }

    /**
     * 预置一件商品，模拟「库里已经有这条记录」。
     *
     * @param product 商品，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public Product seed(Product product) {
        if (product.getId() == null) {
            product.setId(nextId++);
        }
        if (product.getDeleted() == null) {
            product.setDeleted(0);
        }
        rows.put(product.getId(), product);
        return product;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 商品 ID
     * @return 商品；不存在时返回 null
     */
    public Product get(Long id) {
        return rows.get(id);
    }

    /**
     * 表中当前的记录数，供测试断言「有没有多出记录」。
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
            case "insert" -> insert((Product) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectOnSaleList" -> selectOnSaleList();
            case "selectPageBy" -> selectPageBy(args);
            case "updateProduct" -> updateProduct((Product) args[0]);
            case "deleteById" -> deleteById((Long) args[0]);
            case "deductStock" -> deductStock(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeProductMapper 中补上对应实现");
        };
    }

    // ------------------------------------------------------------------
    // 以下是各方法的内存实现
    // ------------------------------------------------------------------

    /**
     * 插入商品，模拟自增主键与审计字段的填充。
     *
     * @param product 待插入的商品
     * @return 受影响行数，恒为 1
     */
    private int insert(Product product) {
        if (product.getId() == null) {
            product.setId(nextId++);
        }
        if (product.getDeleted() == null) {
            product.setDeleted(0);
        }
        if (product.getCreatedAt() == null) {
            product.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        }
        if (product.getUpdatedAt() == null) {
            product.setUpdatedAt(product.getCreatedAt());
        }
        rows.put(product.getId(), product);
        return 1;
    }

    /**
     * 按 ID 查未删除的商品。
     *
     * @param id 商品 ID
     * @return 商品；不存在或已删除时返回 null
     */
    private Product selectById(Long id) {
        Product product = rows.get(id);
        return isAlive(product) ? product : null;
    }

    /**
     * 查全部上架商品，按排序值与 ID 升序。对应真 SQL 的
     * {@code WHERE enabled = 1 ORDER BY sort_no, id}。
     *
     * @return 上架商品列表
     */
    private List<Product> selectOnSaleList() {
        return rows.values().stream()
                .filter(FakeProductMapper::isAlive)
                .filter(p -> Integer.valueOf(1).equals(p.getEnabled()))
                .sorted(Comparator.comparing(Product::getSortNo,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Product::getId))
                .toList();
    }

    /**
     * 分页查询商品。对应真 SQL 的两个可空筛选条件与两种排序。
     *
     * <p>直接在被传入的 {@code IPage} 上写总数与当前页记录 ——
     * 真实环境下这件事是分页插件替你做的，内存实现里得自己来。
     *
     * <p>⚠️ <b>两种排序的次序要与 SQL 逐字一致</b>：默认 {@code sort_no ASC, id DESC}，
     * 按库存时 {@code stock ASC, id ASC}。不一致的话，单测与真库会得出相反的结论，
     * 而两边都「通过」—— 这正是本项目反复提醒的那类静默错误。
     *
     * @param args 依次为分页对象、名称关键词、上架状态、是否按库存升序
     * @return 填好结果的分页对象
     */
    @SuppressWarnings("unchecked")
    private IPage<Product> selectPageBy(Object[] args) {
        IPage<Product> page = (IPage<Product>) args[0];
        String keyword = (String) args[1];
        Integer enabled = (Integer) args[2];
        boolean stockAsc = (Boolean) args[3];

        Comparator<Product> order = stockAsc
                ? Comparator.comparing(Product::getStock,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Product::getId)
                : Comparator.comparing(Product::getSortNo,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Product::getId, Comparator.reverseOrder());

        List<Product> matched = rows.values().stream()
                .filter(FakeProductMapper::isAlive)
                .filter(p -> keyword == null || keyword.isEmpty()
                        || (p.getName() != null && p.getName().contains(keyword)))
                .filter(p -> enabled == null || enabled.equals(p.getEnabled()))
                .sorted(order)
                .toList();

        page.setTotal(matched.size());
        int from = (int) ((page.getCurrent() - 1) * page.getSize());
        if (from >= matched.size()) {
            page.setRecords(List.of());
        } else {
            int to = (int) Math.min(from + page.getSize(), matched.size());
            page.setRecords(matched.subList(from, to));
        }
        return page;
    }

    /**
     * 全量更新商品（后台改名、调价、改库存走这一条）。
     *
     * <p><b>对应真 SQL 的 {@code UPDATE ... SET cover = #{cover} ...}
     * 而不是 {@code updateById}</b>：真 SQL 会把 null 也写进去（全量替换），
     * 而 MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段。
     * 假实现若照着后者写，「撤掉封面」这类用例在单测里就永远是「通过」的，
     * 真机上却做不到 —— 集成测试里有专门一条钉住真 SQL 的行为。
     *
     * <p>内存实现里存的本来就是同一个对象引用，所以这里只需判在不在、
     * 刷一下 {@code updatedAt}。
     *
     * @param product 待更新的商品
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int updateProduct(Product product) {
        if (selectById(product.getId()) == null) {
            return 0;
        }
        product.setUpdatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        rows.put(product.getId(), product);
        return 1;
    }

    /**
     * 逻辑删除商品 —— 把 {@code deleted} 置 1 而不是移除记录。
     *
     * <p>真实环境下这句话是 MyBatis-Plus 按全局配置改写出来的，
     * 假实现照做，否则「删掉之后查不到」这件事在单测里就成了假象。
     *
     * @param id 商品 ID
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int deleteById(Long id) {
        Product product = selectById(id);
        if (product == null) {
            return 0;
        }
        product.setDeleted(1);
        return 1;
    }

    /**
     * 扣减库存，带库存条件。忠实照搬真 SQL 的
     * {@code WHERE id = ? AND stock >= ? AND deleted = 0}。
     *
     * @param args 依次为商品 ID、扣减数量
     * @return 受影响行数；0 表示库存不足或商品不存在
     */
    private int deductStock(Object[] args) {
        Product product = selectById((Long) args[0]);
        int quantity = (Integer) args[1];
        if (product == null || product.getStock() == null || product.getStock() < quantity) {
            return 0;
        }
        product.setStock(product.getStock() - quantity);
        return 1;
    }

    /**
     * 判断记录是否存在且未被逻辑删除。
     *
     * @param product 商品，可为 null
     * @return 有效返回 true
     */
    private static boolean isAlive(Product product) {
        return product != null && !Integer.valueOf(1).equals(product.getDeleted());
    }
}
