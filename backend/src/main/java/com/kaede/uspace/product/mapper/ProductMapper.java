package com.kaede.uspace.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.product.entity.Product;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 实体商品的数据访问接口。
 *
 * <p><b>手写 SQL 必须自己带 {@code deleted = 0}</b> —— 全局逻辑删除配置
 * 只管 MyBatis-Plus 自己生成的方法，管不到注解里的 SQL。漏写的后果不是报错，
 * 而是「删掉的商品又冒出来」，所以每一条都要带上。
 */
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 查全部上架商品，按排序值与 ID 升序。
     *
     * <p>供用户端的商品列表使用。下架上不在这里 —— 那是后台列表的事。
     *
     * <p>排序带 {@code id} 兜底不是可有可无：{@code sort_no} 由管理员随手填，
     * 撞值很常见，只按它排的话同值行的先后由存储引擎决定 ——
     * 同一批数据两次查询可能给出不同顺序，翻页时表现为「某件商品没出现过」。
     *
     * @return 上架商品列表；没有则返回空列表
     */
    @Select("""
            SELECT *
              FROM biz_product
             WHERE deleted = 0
               AND enabled = 1
             ORDER BY sort_no, id
            """)
    List<Product> selectOnSaleList();

    /**
     * 按名字精确查一件<b>未删除</b>的商品（群里的下单指令用它找商品）。
     *
     * <p><b>返回单个对象而不是列表</b>：{@code uk_name} 保证同名至多一条。
     * 返回 List 等于把「可能有多个」这个不存在的前提写进接口，
     * 而调用方迟早会为它写一段永远不会执行的分支 —— 那比不写更糟，
     * 因为读代码的人会以为那种情况真的会发生。
     *
     * <p>⚠️ <b>不筛 {@code enabled}</b>：调用方要能区分「没有这个商品」与
     * 「这个商品已下架」—— 前者让用户去发 {@code fw菜单} 看准确名称，
     * 后者要告诉他等上架。两句话不一样，所以状态交给调用方判。
     *
     * <p>名字<b>精确匹配</b>（去掉首尾空白之后）。刻意不做「忽略空格 / 大小写」的
     * 模糊匹配：商品名里带空格是常事（「王老吉 250ml（绿）」），
     * 一旦放宽就同时放宽了「匹配到两件」的可能，而唯一键管不住那种模糊等价。
     * 名字写不准确时由 {@code fw菜单} 兜住 —— 那里给的是可复制的准确名称。
     *
     * @param name 商品名，调用方先去首尾空白
     * @return 商品；不存在或已逻辑删除时返回 null
     */
    @Select("SELECT * FROM biz_product WHERE name = #{name} AND deleted = 0")
    Product selectLiveByName(@Param("name") String name);

    /**
     * 数一数同名商品有几件 —— <b>含已逻辑删除的</b>。
     *
     * <p>⚠️ <b>刻意不写 {@code deleted = 0}，与上面的 {@code selectLiveByName} 正好相反</b>。
     * 它对应的是 {@code uk_name} 那条唯一键，而那条键<b>不含 deleted</b> ——
     * 逻辑删除过的商品仍然占着名字。两者口径差这一点点的后果很具体：
     * 后台提示「名字可用」，保存时却撞唯一键，用户拿到一句 500 而不知道该改哪里。
     *
     * <p>与 {@code DeviceMapper#countByDeviceNo} 的差别也在这里（那个筛了 {@code deleted}），
     * <b>不要照着它改</b>。
     *
     * @param name      商品名
     * @param excludeId 要排除的商品 ID（改自己时传自己的 ID），新增时传 null
     * @return 同名商品数；0 表示这个名字可用
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_product
             WHERE name = #{name}
               AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    int countByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /**
     * 分页查询商品，供运营后台使用。
     *
     * <p>两个筛选条件都可空，空则不过滤 —— 用 {@code #{x} IS NULL OR ...}
     * 的写法而不是动态标签，与用户列表、月卡列表的查询保持一致。
     *
     * <p>关键词按名称模糊匹配，{@code LIKE} 的写法与设备列表同款。
     *
     * <p><b>排序只有两种，用 {@code <choose>} 从两句写死的 ORDER BY 里挑一句</b>，
     * 不用 {@code ${}} 拼字符串 —— 那才是注入口。这一点很要紧：
     * 排序字段一旦能被调用方拼进来，就等于把 SQL 的一部分交出去了。
     * <ul>
     *   <li>默认（{@code stockAsc = false}）：{@code sort_no, id DESC} ——
     *       运营在编辑表单里调的展示顺序</li>
     *   <li>{@code stockAsc = true}：{@code stock ASC, id ASC} ——
     *       库存少的在前，补货优先</li>
     * </ul>
     * 两种排序都带 {@code id} 兜底：{@code sort_no} 会撞值、{@code stock} 更会，
     * 只按业务列排的话，同值行的先后由存储引擎决定，翻页时表现为「某条没出现过」。
     *
     * <p>⚠️ <b>排序必须做在 SQL 里，不能拿回前端排</b>：结果是分页的，
     * 前端只能排当前这一页 —— 第二页可能藏着比本页更少的库存，
     * 而运营看的是「最上面那条最少」。
     *
     * @param page     分页参数，由 MyBatis-Plus 的分页插件处理
     * @param keyword  名称关键词，可空
     * @param enabled  上架状态（1/0），可空
     * @param stockAsc 是否按库存从少到多排
     * @return 分页结果
     */
    @Select("""
            <script>
            SELECT *
              FROM biz_product
             WHERE deleted = 0
               AND (#{keyword} IS NULL OR #{keyword} = '' OR name LIKE CONCAT('%', #{keyword}, '%'))
               AND (#{enabled} IS NULL OR enabled = #{enabled})
             ORDER BY
            <choose>
                <when test="stockAsc">stock ASC, id ASC</when>
                <otherwise>sort_no, id DESC</otherwise>
            </choose>
            </script>
            """)
    IPage<Product> selectPageBy(IPage<Product> page,
                                @Param("keyword") String keyword,
                                @Param("enabled") Integer enabled,
                                @Param("stockAsc") boolean stockAsc);

    /**
     * 按 ID 全量更新商品（后台修改走这一条）。
     *
     * <p><b>用显式 SQL 而非 {@code updateById}</b>，是为了配合 PUT 的全量替换语义：
     * 传 null 即清空该字段（如撤掉封面图、清空描述）。{@code updateById} 的默认
     * 字段策略是 {@code NOT_NULL} —— <b>会跳过 null 字段</b>，那样封面就永远删不掉了，
     * 而且不报任何错：接口返回 200，前端刷新后旧图还在。
     *
     * <p>本项目的模块 1、模块 3、模块 4、公告包<b>全部踩过同一个坑</b>，
     * 所以这里照同一套写法来，不图 {@code updateById} 的省事。
     *
     * <p><b>只改本表自己的列</b>：{@code created_at} 由框架在插入时填过，
     * 这里不碰；{@code deleted} 归 {@code deleteById} 管。
     *
     * @param product 商品，{@code id} 必须非空
     * @return 受影响行数；0 表示商品不存在或已逻辑删除
     */
    @Update("""
            UPDATE biz_product
               SET name        = #{name},
                   cover       = #{cover},
                   description = #{description},
                   price       = #{price},
                   stock       = #{stock},
                   enabled     = #{enabled},
                   sort_no     = #{sortNo},
                   updated_at  = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateProduct(Product product);

    /**
     * 扣减库存 —— <b>带库存条件的原子更新</b>。
     *
     * <p>{@code AND stock >= #{quantity}} 是它全部的意义所在：
     * 两人同时买最后一件时，只有一条 UPDATE 能拿到 1 行受影响，
     * 另一条拿到 0。调用方据此判定「确实卖光了」并让该笔支付进人工处理 ——
     * <b>绝不能在支付回调里抛异常</b>，那会让支付平台不断重推一笔
     * 永远处理不了的通知。
     *
     * <p>不用「先查库存、再判断、再更新」的写法：那中间的窗口足够
     * 让两次请求都读到「还有 1 件」，然后双双扣成 -1。
     *
     * <p>⚠️ <b>别与 {@link #updateStock} 搞混</b>：那一条把库存<b>设成</b>给定值
     * （盘点、补货走它），这一条按量<b>扣减</b>（卖出走它）。传错的表现极像 ——
     * 「补货到 20 件」与「再进 20 件」差之毫厘，库存数却从此一路错下去，且不报任何错。
     *
     * @param id       商品 ID
     * @param quantity 扣减数量，必须为正
     * @return 受影响行数；0 表示库存不足或商品不存在（含已逻辑删除）
     */
    @Update("""
            UPDATE biz_product
               SET stock      = stock - #{quantity},
                   updated_at = NOW()
             WHERE id = #{id}
               AND stock >= #{quantity}
               AND deleted = 0
            """)
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 把库存<b>设成给定的值</b> —— 后台盘点与群里的库存指令走这一条。
     *
     * <p>⚠️ <b>与 {@link #deductStock} 的区别是语义，不是写法</b>：
     * 那一条按数量<b>扣减</b>（卖出去几件减几件，带 {@code stock >= ?} 条件），
     * 这一条是「盘点后是多少就是多少」。两者传错了的表现很接近 ——
     * 「补货到 20 件」与「再进 20 件」差之毫厘，而库存数从此一路错下去，
     * 且不会报任何错。调用方认准自己的语义再调。
     *
     * <p><b>为什么不走 {@code updateById}</b>：那是全量替换的语义，而这里只动一列。
     * 走它得先把整条商品读出来再原样传回，中间若有别人改了名称就会被静默覆盖 ——
     * 与模块 4「机台单独改状况」是同一个理由（见 {@code DeviceMapper#updateStatus}）。
     *
     * @param id    商品 ID
     * @param stock 新的库存值，调用方保证非负
     * @return 受影响行数；0 表示商品不存在或已逻辑删除
     */
    @Update("""
            UPDATE biz_product
               SET stock      = #{stock},
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int updateStock(@Param("id") Long id, @Param("stock") Integer stock);
}
