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
     * 分页查询商品，供运营后台使用。
     *
     * <p>两个筛选条件都可空，空则不过滤 —— 用 {@code #{x} IS NULL OR ...}
     * 的写法而不是动态标签，与用户列表、月卡列表的查询保持一致。
     *
     * <p>关键词按名称模糊匹配，{@code LIKE} 的写法与设备列表同款。
     *
     * @param page    分页参数，由 MyBatis-Plus 的分页插件处理
     * @param keyword 名称关键词，可空
     * @param enabled 上架状态（1/0），可空
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_product
             WHERE deleted = 0
               AND (#{keyword} IS NULL OR #{keyword} = '' OR name LIKE CONCAT('%', #{keyword}, '%'))
               AND (#{enabled} IS NULL OR enabled = #{enabled})
             ORDER BY sort_no, id DESC
            """)
    IPage<Product> selectPageBy(IPage<Product> page,
                                @Param("keyword") String keyword,
                                @Param("enabled") Integer enabled);

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
     * <p><b>本方法是本表唯一的写入口</b>，后台改商品时传的是「新的库存值」
     * 而不是「扣减量」，两者不要混。
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
}
