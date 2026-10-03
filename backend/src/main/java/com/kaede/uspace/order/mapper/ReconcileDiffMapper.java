package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.ReconcileDiff;
import com.kaede.uspace.order.reconcile.ReconcileDiffType;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 对账差异的数据访问接口。
 *
 * <p><b>本表没有 {@code deleted} 列</b>（差异是财务过程的记录），
 * 手写 SQL 里一个 {@code deleted = 0} 都不要写。
 *
 * <p>新增走 MyBatis-Plus 自带的 {@code insert}（同样会回填主键），
 * 本接口不重新声明。
 *
 * <p><b>三条查询的判空责任在调用方</b>：{@link #countUnhandledByBatchIds}、
 * {@link #selectUnhandledByProofIds}、{@link #selectUnhandledByPaymentNos}
 * 都用了 {@code IN <foreach>}，而<b>空集合会拼出 {@code IN ()} 这种语法错误</b>。
 * 调用方必须先判空再调 —— 这不是可以靠 SQL 兜住的事：
 * 把 {@code <if>} 包上去虽然不会报错，但会让「条件被整个吃掉」变成
 * 「查全部未处理差异」，那是一个更隐蔽的错误。
 */
public interface ReconcileDiffMapper extends BaseMapper<ReconcileDiff> {

    /**
     * 后台分页查询某个批次的差异。
     *
     * <p>排序是<b>刻意设计的</b>，两条依次生效：
     * <ol>
     *   <li><b>待处理的排在已处理之前</b> —— 不筛状态时（默认视图），
     *       管理员最关心的是待办</li>
     *   <li><b>按类型优先级</b> —— {@code FIELD(...)} 里的顺序就是
     *       {@link ReconcileDiffType} 的声明顺序（最紧急的排最前），
     *       由 {@code ReconcileDiffType#FIELD_ORDER} 提供，并有单测钉住它与枚举一致</li>
     * </ol>
     * 最后按 {@code id} 兜底，保证分页时顺序是确定的 —— 少了它，
     * 两次查询的同一页可能给出不同的行，翻页时会看到重复或漏掉。
     *
     * @param page     分页参数
     * @param batchId  批次 ID
     * @param diffType 类型筛选，为 null 或空串时不过滤
     * @param handled  处理状态筛选，为 null 时不过滤（0 = 只看待处理，1 = 只看已处理）
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_reconcile_diff
             WHERE batch_id = #{batchId}
               AND (#{diffType} IS NULL OR #{diffType} = '' OR diff_type = #{diffType})
               AND (#{handled} IS NULL OR handled = #{handled})
             ORDER BY handled ASC, FIELD(diff_type, """ + ReconcileDiffType.FIELD_ORDER + "), id ASC")
    IPage<ReconcileDiff> selectPageForAdmin(IPage<ReconcileDiff> page,
                                            @Param("batchId") Long batchId,
                                            @Param("diffType") String diffType,
                                            @Param("handled") Integer handled);

    /**
     * 标记一条差异已处理。
     *
     * <p><b>带状态守卫</b>（{@code AND handled = 0}）：两个管理员同时看对账结果时，
     * 后一个的结论不该覆盖前一个的 —— 受影响行数为 0 就是「已经被别人处理过了」。
     * 与 {@code PaymentProofMapper.confirm} 是同一套做法。
     *
     * <p><b>刻意不提供「取消已处理」</b>：那会让 {@code handled} 变成可反复翻转的状态、
     * 多一个入口，而标错了的条目仍然筛得出来、看得见。
     *
     * @param id      差异 ID
     * @param adminId 处理人管理员 ID
     * @param note    处理备注，可为 null
     * @return 受影响行数；0 表示这条已被别人处理过，或 ID 不存在
     */
    @Update("""
            UPDATE biz_reconcile_diff
               SET handled     = 1,
                   handle_note = #{note},
                   handled_by  = #{adminId},
                   handled_at  = NOW()
             WHERE id = #{id}
               AND handled = 0
            """)
    int handle(@Param("id") Long id, @Param("adminId") Long adminId, @Param("note") String note);

    /**
     * 数一数某个批次里各类差异各有多少条。
     *
     * <p>给批次详情的类型筛选按钮用（「未填流水号 12」），一次查回全部类型。
     *
     * @param batchId 批次 ID
     * @return 各类型的条数；没有差异的类型不会出现在结果里
     */
    @Select("""
            SELECT diff_type, COUNT(*) AS cnt
              FROM biz_reconcile_diff
             WHERE batch_id = #{batchId}
             GROUP BY diff_type
            """)
    List<ReconcileDiffTypeCount> countByBatchGroupByType(@Param("batchId") Long batchId);

    /**
     * 数一数这批批次各还有几条差异没处理。
     *
     * <p>给批次列表的「未处理 3」用。一次查回全部批次，避免 N+1。
     *
     * <p>⚠️ {@code batchIds} <b>必须非空</b>，空集合会拼出 {@code IN ()} 语法错误，
     * 见类注释。
     *
     * @param batchIds 批次 ID 列表，非空
     * @return 各批次的未处理条数；全处理完的批次不会出现在结果里
     */
    @Select("""
            <script>
            SELECT batch_id, COUNT(*) AS cnt
              FROM biz_reconcile_diff
             WHERE handled = 0
               AND batch_id IN
               <foreach collection="batchIds" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
             GROUP BY batch_id
            </script>
            """)
    List<ReconcileBatchDiffCount> countUnhandledByBatchIds(@Param("batchIds") List<Long> batchIds);

    /**
     * 取「这些凭证身上还没处理的差异」。
     *
     * <p>给对账的<b>去重</b>用：同一笔凭证如果上个月就报过「账单里找不到」，
     * 而这个月重跑又发现同样的事，就不该再堆一条 —— 管理员没处理完就重复堆条目，
     * 只会让他干脆不看了。
     *
     * <p>只 SELECT 出判重需要的两列，其余字段为 null；调用方按
     * {@code (diffType, proofId)} 建集合即可。
     *
     * <p>⚠️ {@code proofIds} <b>必须非空</b>，见类注释。
     *
     * @param proofIds 凭证 ID 列表，非空
     * @return 未处理差异，只需 {@code diff_type} 与 {@code proof_id} 两列
     */
    @Select("""
            <script>
            SELECT diff_type, proof_id
              FROM biz_reconcile_diff
             WHERE handled = 0
               AND proof_id IN
               <foreach collection="proofIds" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
            </script>
            """)
    List<ReconcileDiff> selectUnhandledByProofIds(@Param("proofIds") List<Long> proofIds);

    /**
     * 取「这些流水号身上还没处理的差异」。
     *
     * <p>与 {@link #selectUnhandledByProofIds} 是同一件事的两半：
     * {@code BILL_ONLY} 那一类<b>没有凭证可指</b>（系统里压根没有凭证认领那笔钱），
     * 所以它只能按流水号判重。
     *
     * <p>⚠️ {@code paymentNos} <b>必须非空</b>，见类注释。
     *
     * @param paymentNos 归一化后的流水号列表，非空
     * @return 未处理差异，只需 {@code diff_type} 与 {@code payment_no} 两列
     */
    @Select("""
            <script>
            SELECT diff_type, payment_no
              FROM biz_reconcile_diff
             WHERE handled = 0
               AND payment_no IN
               <foreach collection="paymentNos" item="no" open="(" separator="," close=")">
                 #{no}
               </foreach>
            </script>
            """)
    List<ReconcileDiff> selectUnhandledByPaymentNos(@Param("paymentNos") List<String> paymentNos);
}
