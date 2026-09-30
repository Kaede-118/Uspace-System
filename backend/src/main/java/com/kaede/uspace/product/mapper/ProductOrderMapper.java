package com.kaede.uspace.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.product.entity.ProductOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品购买单的数据访问接口。
 *
 * <p>它是商品支付链路的落点：支付回调按 {@link #selectByOrderNo} 反查，
 * 由 {@link #markPaid} 转已支付，随后由 {@code ProductPaymentTargetHandler}
 * 扣减库存。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b>，理由同商品表。
 */
public interface ProductOrderMapper extends BaseMapper<ProductOrder> {

    /**
     * 按购买单号查单。
     *
     * <p>供支付回调使用 —— 回调来自支付平台，没有「当前用户」这个概念，
     * 身份由<b>验签</b>保证而不是由归属校验保证。
     *
     * @param orderNo 购买单号（即商户订单号）
     * @return 购买单；不存在时返回 null
     */
    @Select("""
            SELECT *
              FROM biz_product_order
             WHERE order_no = #{orderNo}
               AND deleted = 0
            """)
    ProductOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 支付成功：转入已支付并写全支付字段。
     *
     * <p><b>{@code AND status = 'PENDING_PAYMENT'} 是幂等的关键一道</b>：
     * 支付平台会重推通知，两条回调同时进来时只有一个能拿到 1 行受影响，
     * 另一个拿到 0 —— 调用方据此判定「已被处理过」，不再重复扣库存、
     * 也不再重复累加用户的消费额。
     *
     * <p>本方法返回 0 时，调用方<b>绝不能再去扣库存</b>。
     *
     * @param id            购买单 ID
     * @param paymentMethod 支付通道名
     * @param paymentNo     支付平台交易号
     * @param paidAt        支付完成时刻
     * @return 受影响行数；0 表示单子不是待支付状态，或记录不存在
     */
    @Update("""
            UPDATE biz_product_order
               SET status         = 'PAID',
                   payment_method = #{paymentMethod},
                   payment_no     = #{paymentNo},
                   paid_at        = #{paidAt},
                   updated_at     = NOW()
             WHERE id = #{id}
               AND status = 'PENDING_PAYMENT'
               AND deleted = 0
            """)
    int markPaid(@Param("id") Long id,
                 @Param("paymentMethod") String paymentMethod,
                 @Param("paymentNo") String paymentNo,
                 @Param("paidAt") LocalDateTime paidAt);

    /**
     * 关闭待支付的购买单（用户主动取消）。
     *
     * <p>状态守卫 {@code status = 'PENDING_PAYMENT'} 不能少：没有它，
     * 一个已经支付成功的单子会被「取消」掉，而库存已经扣了 ——
     * 于是出现「单子关闭、库存少了」的不一致。
     *
     * @param id 购买单 ID
     * @return 受影响行数；0 表示单据不是待支付状态，或记录不存在
     */
    @Update("""
            UPDATE biz_product_order
               SET status     = 'CLOSED',
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'PENDING_PAYMENT'
               AND deleted = 0
            """)
    int closePending(@Param("id") Long id);

    /**
     * 分页查询购买单，用户端与运营后台共用。
     *
     * <p>两个筛选条件都可空，空则不过滤。用户端固定传自己的 {@code userId}，
     * 后台可传 null（查全部）或指定某个用户 —— 与月卡的两套查询不同，
     * 那两份代码一字不差，合并成一条更省事，也少一处会漂移的地方。
     *
     * @param page   分页参数，由 MyBatis-Plus 的分页插件处理
     * @param userId 购买人 ID，可空
     * @param status 状态，可空
     * @return 分页结果，最近的在前
     */
    @Select("""
            SELECT *
              FROM biz_product_order
             WHERE deleted = 0
               AND (#{userId} IS NULL OR user_id = #{userId})
               AND (#{status} IS NULL OR #{status} = '' OR status = #{status})
             ORDER BY id DESC
            """)
    IPage<ProductOrder> selectPageBy(IPage<ProductOrder> page,
                                     @Param("userId") Long userId,
                                     @Param("status") String status);

    /**
     * 统计一批商品各自被<b>未超时</b>的待支付单占掉多少件。
     *
     * <p>供「可售量 = 库存 − 占用量」这条软检查使用（见
     * {@code ProductService#createOrder}）：它不锁库存，所以理论上仍有竞态窗口，
     * 但把「已经有人在下单但还没付」这段最容易撞的时间差挡掉了。
     *
     * <p><b>⚠️ 是 {@code SUM(quantity)} 不是 {@code COUNT(*)}</b>：
     * 一笔「买 5 件」的待支付单占掉的是 5 件库存，不是 1 件。
     * 写成 {@code COUNT(*)} 不会有任何报错，只会让可售量比实际多出一大截 ——
     * 表现为「明明下单成功了，付款后却说没货」。
     *
     * <p><b>为什么要带 {@code created_at > since}</b>：超时的待支付单不再占位。
     * 否则用户下单后不付款就会把那件商品永久占住 —— 而且商品单不像月卡那样
     * 会被「下次购买时顺手关掉」（商品可以买多次，没有那个触发点）。
     *
     * <p><b>没有待支付单的商品不会出现在结果里</b>（{@code GROUP BY} 的本性），
     * 调用方要按「查不到即 0」处理。
     *
     * <p>⚠️ <b>{@code productIds} 必须非空</b>：空集合会拼出 {@code IN ()}，
     * 那是语法错误。调用方在列表为空时应当直接跳过这次查询。
     *
     * @param productIds 商品 ID 列表，非空
     * @param since      判定起点，早于它的待支付单视为已超时、不再占位
     * @return 各商品的占用量；全部为 0 时返回空列表
     */
    @Select("""
            <script>
            SELECT product_id AS productId, SUM(quantity) AS pendingQuantity
              FROM biz_product_order
             WHERE deleted = 0
               AND status = 'PENDING_PAYMENT'
               AND created_at &gt; #{since}
               AND product_id IN
               <foreach collection="productIds" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
             GROUP BY product_id
            </script>
            """)
    List<ProductPendingCount> countPendingByProduct(@Param("productIds") List<Long> productIds,
                                                    @Param("since") LocalDateTime since);
}
