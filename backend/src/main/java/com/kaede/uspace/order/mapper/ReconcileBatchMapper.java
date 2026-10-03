package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.ReconcileBatch;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对账批次的数据访问接口。
 *
 * <p><b>本表没有 {@code deleted} 列</b>（批次是财务过程的记录，见
 * {@link ReconcileBatch} 的类注释），所以手写 SQL 里<b>一个 {@code deleted = 0}
 * 都不要写</b> —— 照抄别的 Mapper 会直接 {@code Unknown column}。
 * 这与 {@code PaymentProofMapper}、{@code AccessRecordMapper} 是同一个坑。
 *
 * <p><b>批次的新增走 MyBatis-Plus 自带的 {@code insert}</b>，本接口不重新声明它。
 * 理由是它会<b>回填主键</b>，而 Service 拿到批次 ID 后要立刻拿它去回写凭证的
 * {@code reconcile_batch_id} —— 没有 ID 这一步就断在这里。
 * 对照 {@code PaymentProofMapper.upsert}：那里刻意不回填（手写的
 * {@code ON DUPLICATE KEY UPDATE} 在冲突路径上拿不到可靠的 ID），
 * 两处的差别是有原因的，不是这里漏写了什么。
 */
public interface ReconcileBatchMapper extends BaseMapper<ReconcileBatch> {

    /**
     * 后台分页查询批次。
     *
     * <p>排序固定为「新的在前」：管理员看这一页就是想知道「最近几次对得怎么样」。
     *
     * @param page    分页参数，由 MyBatis-Plus 的分页插件处理
     * @param storeId 门店 ID
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_reconcile_batch
             WHERE store_id = #{storeId}
             ORDER BY created_at DESC, id DESC
            """)
    IPage<ReconcileBatch> selectPageForAdmin(IPage<ReconcileBatch> page,
                                             @Param("storeId") Long storeId);

    /**
     * 修正批次的匹配笔数。
     *
     * <p><b>只在一种情况下被调用</b>：认领凭证的 UPDATE 返回的受影响行数少于
     * 预期 —— 说明有并发的另一个批次先把某几条凭证认领走了。
     * 那时批次上记的数就比实际多了，得按实际改回来。
     *
     * <p>比「先查再更新」可靠：查与更新之间有竞态窗口，而这里要的正是
     * 「以数据库最终认下的行为准」。
     *
     * @param id           批次 ID
     * @param matchedCount 实际的匹配笔数
     * @return 受影响行数
     */
    @Update("""
            UPDATE biz_reconcile_batch
               SET matched_count = #{matchedCount}
             WHERE id = #{id}
            """)
    int updateMatchedCount(@Param("id") Long id, @Param("matchedCount") int matchedCount);
}
