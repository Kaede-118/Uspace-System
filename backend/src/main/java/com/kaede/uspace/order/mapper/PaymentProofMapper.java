package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.PaymentProof;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 付款凭证的数据访问接口。
 *
 * <p><b>本表没有 {@code deleted} 列</b>（凭证是财务凭据，见
 * {@code PaymentProof} 的类注释），所以手写 SQL 里<b>一个 {@code deleted = 0}
 * 都不要写</b> —— 照抄别的 Mapper 会直接 {@code Unknown column}。
 * 这与 {@code AccessRecordMapper} 是同一个坑。
 *
 * <p><b>两条 UPDATE 都带状态守卫</b>（{@code AND verify_status = 'SUBMITTED'}）：
 * 两个管理员同时点「确认」时只有一个能改成功，另一个拿到 0 行；
 * 调用方据此提示「这条已被处理过」。比「先查状态再更新」可靠 ——
 * 查与更新之间有竞态窗口，而复核是资金结论，改两次的后果不是小事。
 */
public interface PaymentProofMapper extends BaseMapper<PaymentProof> {

    /**
     * 按收款目标查凭证。
     *
     * <p>这是本表最常用的一条查询：提交时要看「这个目标是不是已经有凭证了」
     * （决定走哪条分支），用户端要回显「我提交了什么」，走的是
     * {@code uk_target} 唯一索引，一次等值查找。
     *
     * @param targetType 收款类型名，见 {@code PaymentTargetType}
     * @param targetId   目标 ID
     * @return 凭证；这个目标还没提交过时返回 null
     */
    @Select("""
            SELECT *
              FROM biz_payment_proof
             WHERE target_type = #{targetType}
               AND target_id   = #{targetId}
            """)
    PaymentProof selectByTarget(@Param("targetType") String targetType,
                                @Param("targetId") Long targetId);

    /**
     * 写入或更新一条凭证（按 {@code uk_target} 判重）。
     *
     * <p><b>为什么是 upsert 而不是「先查再决定插还是改」</b>：手机上连点两下提交
     * 是高频事件，两次请求会同时走到「查不到凭证 → 插入」那一支，
     * 第二次撞唯一索引抛 {@code DuplicateKeyException}，用户看到的是
     * 「系统错误」而不是「提交成功」。交给数据库一条原子语句处理，
     * 这种竞态就不存在了。
     *
     * <p><b>重复提交会把旧结论清掉</b>：状态翻回 {@code SUBMITTED}、
     * 驳回原因与复核人一并置空 —— 用户重新交了一张图，管理员当然要重新看。
     * 同理，{@code ocr_*} 三列<b>被换成本次提交带上来的新值</b>：
     * 它们是对<b>旧图</b>的识别结果，换图之后就不再对应任何东西了。
     * （2026-09-30 起这一支写的是 {@code VALUES(ocr_*)} 而不是 {@code NULL} ——
     * 识别在上传截图时完成，结果随提交请求一起回来，见 {@code ProofImageVo}。
     * 新图若什么都没识别出，带上来的就是 null，写进去自然也是「没有」。）
     *
     * <p><b>{@code created_at} 只在插入时写，冲突时不改</b> —— 它记的是
     * 「这条凭证第一次提交是什么时候」，重交不该刷新它。
     *
     * <p><b>{@code risk_flag} 会被清掉</b>，随后由 Service 重新检测：
     * 用户抄错一位、换了个流水号重交之后，旧标记不该跟着他 ——
     * 那会让后台的「有风险」列表越积越多假警报，最终管理员干脆不看它了。
     * 清了之后若仍然冲突（同号还有别人在用），检测那一步会立刻标回来。
     *
     * <p>⚠️ <b>本方法不回填主键</b>，插入之后 {@code proof.getId()} 仍是 null。
     * MyBatis-Plus 只为它<b>自带</b>的 {@code insert} 配了 {@code useGeneratedKeys}，
     * 手写 {@code @Insert} 不在其列；而这里刻意不加 {@code @Options} ——
     * 加了的话插入路径能拿到 ID，冲突路径拿到的却是 MySQL 在
     * {@code ON DUPLICATE KEY UPDATE} 下返回的那个不可靠的值
     *（要与 {@code id = LAST_INSERT_ID(id)} 配合才准）。
     * 一个「有时对、有时错」的 ID 比一个恒为 null 的 ID 危险得多。
     * <b>需要 ID 时用 {@link #selectByTarget} 再查一次</b>，写库的调用方本来也不该依赖它。
     *
     * @param proof 凭证实体；{@code targetType} / {@code targetId} / {@code userId} /
     *              {@code amount} / {@code orderNo} / {@code proofUrl} 必填
     * @return 受影响行数：插入记 1，冲突走 UPDATE 时记 2（MySQL 的约定），
     *         <b>不要拿它判断业务结果</b>
     */
    @Insert("""
            INSERT INTO biz_payment_proof
              (target_type, target_id, order_no, user_id, amount,
               pay_qr_id, proof_url, payment_no,
               ocr_payment_no, ocr_amount, ocr_text,
               verify_status, created_at, updated_at)
            VALUES
              (#{targetType}, #{targetId}, #{orderNo}, #{userId}, #{amount},
               #{payQrId}, #{proofUrl}, #{paymentNo},
               #{ocrPaymentNo}, #{ocrAmount}, #{ocrText},
               'SUBMITTED', NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              proof_url      = VALUES(proof_url),
              payment_no     = VALUES(payment_no),
              amount         = VALUES(amount),
              pay_qr_id      = VALUES(pay_qr_id),
              ocr_payment_no = VALUES(ocr_payment_no),
              ocr_amount     = VALUES(ocr_amount),
              ocr_text       = VALUES(ocr_text),
              risk_flag      = NULL,
              verify_status  = 'SUBMITTED',
              confirmed_by   = NULL,
              confirmed_at   = NULL,
              reject_reason  = NULL,
              updated_at     = NOW()
            """)
    int upsert(PaymentProof proof);

    /**
     * 后台分页查询凭证。
     *
     * <p>排序是<b>刻意设计的</b>，三条依次生效：
     * <ol>
     *   <li><b>有风险标记的排最前</b> —— 那是「同一流水号被多笔凭证引用」，
     *       纯信任制下最值得防的作弊手法，必须第一眼看到</li>
     *   <li><b>待复核的排在已处理的之前</b> —— 不筛状态时（默认视图），
     *       管理员最关心的是待办</li>
     *   <li><b>新的在前</b> —— 同一天的凭证按提交时间倒序</li>
     * </ol>
     * <b>不按「是否已交付」排</b>：那是 {@code PaymentTargetHandler#deliverOnSubmit}
     * 声明的，硬编码进 SQL 会与处理器漂移。它由 VO 的 {@code delivered} 字段
     * 带给前端，由前端在条目上显著标出。
     *
     * @param page         分页参数，由 MyBatis-Plus 的分页插件处理
     * @param verifyStatus 状态筛选，为 null 或空串时不过滤（后台默认视图）
     * @return 分页结果
     */
    @Select("""
            SELECT *
              FROM biz_payment_proof
             WHERE (#{verifyStatus} IS NULL OR #{verifyStatus} = '' OR verify_status = #{verifyStatus})
             ORDER BY (risk_flag IS NOT NULL) DESC,
                      (verify_status = 'SUBMITTED') DESC,
                      created_at DESC,
                      id DESC
            """)
    IPage<PaymentProof> selectPageForAdmin(IPage<PaymentProof> page,
                                           @Param("verifyStatus") String verifyStatus);

    /**
     * 复核通过。
     *
     * <p>只改复核结论，<b>不动目标状态</b> —— 目标该不该转已支付由调用方决定
     * （「提交即交付」的那两类在提交那一刻就已经落账了，复核只是登记）。
     *
     * @param id      凭证 ID
     * @param adminId 复核管理员 ID
     * @return 受影响行数；0 表示这条已被别人复核过，或 ID 不存在
     */
    @Update("""
            UPDATE biz_payment_proof
               SET verify_status = 'CONFIRMED',
                   confirmed_by  = #{adminId},
                   confirmed_at  = NOW(),
                   reject_reason = NULL,
                   updated_at    = NOW()
             WHERE id = #{id}
               AND verify_status = 'SUBMITTED'
            """)
    int confirm(@Param("id") Long id, @Param("adminId") Long adminId);

    /**
     * 复核不通过。
     *
     * <p>{@code reject_reason} 必填 —— 用户重交或申诉时要知道「哪里不对」，
     * 空着的话他只能反复试。Service 层会挡住空原因（库列也留了 200 的宽度，
     * 但那是给文案的余量，不是给空串的）。
     *
     * @param id      凭证 ID
     * @param adminId 复核管理员 ID
     * @param reason  未通过原因
     * @return 受影响行数；0 表示这条已被别人复核过，或 ID 不存在
     */
    @Update("""
            UPDATE biz_payment_proof
               SET verify_status = 'REJECTED',
                   confirmed_by  = #{adminId},
                   confirmed_at  = NOW(),
                   reject_reason = #{reason},
                   updated_at    = NOW()
             WHERE id = #{id}
               AND verify_status = 'SUBMITTED'
            """)
    int reject(@Param("id") Long id, @Param("adminId") Long adminId,
               @Param("reason") String reason);

    // ==================================================================
    // 流水号重复检测
    // ==================================================================

    /**
     * 数一数这个流水号被几条凭证引用过。
     *
     * <p><b>为什么值得单独查一次</b>：一张截图付两单是纯信任制下最省事的作弊手法，
     * 而它留下的痕迹只有一个 —— 同一个流水号出现两次。走 {@code idx_payment_no}
     * 是一次索引扫描，代价远低于它挡下的东西。
     *
     * <p>调用方拿 {@code > 1} 作为判据：等于 1 是正常的（它自己）。
     *
     * @param paymentNo 交易流水号；调用方须先判非空
     * @return 引用该流水号的凭证条数
     */
    @Select("""
            SELECT COUNT(*)
              FROM biz_payment_proof
             WHERE payment_no = #{paymentNo}
            """)
    int countByPaymentNo(@Param("paymentNo") String paymentNo);

    /**
     * 给引用同一流水号的全部凭证打上风险标记。
     *
     * <p><b>标记的是全部而不只是后来者</b>：谁先谁后没有意义，
     * 冲突是双向的 —— 管理员需要看到「这两条用了同一张图」，
     * 而不是「后提交的那条可疑」。两条都标出来，它们在后台列表里
     * 会挨着排到最前面。
     *
     * <p>刻意<b>不拦提交</b>：真要发生也应当让人看见并判断，
     * 而不是让系统静默拒绝一笔可能的正常付款（用户重传时填错一位、
     * 或同一笔钱在两个页面上都提交了一次，都是常见情形）。
     *
     * @param paymentNo 交易流水号；调用方须先判非空
     * @return 受影响行数
     */
    @Update("""
            UPDATE biz_payment_proof
               SET risk_flag  = 'DUPLICATE_PAYMENT_NO',
                   updated_at = NOW()
             WHERE payment_no = #{paymentNo}
            """)
    int markDuplicateByPaymentNo(@Param("paymentNo") String paymentNo);

    // ==================================================================
    // 对账（Phase 5）
    // ==================================================================

    /**
     * 取参与对账的凭证候选：落在时间窗口内的全部凭证。
     *
     * <p><b>⚠️ 这里不筛 {@code verify_status}、也不筛 {@code reconcile_batch_id} ——
     * 这是整个对账功能里最容易写错的一处，改动前请读完这段。</b>
     *
     * <p>看起来「跳过已经对过的凭证」是很自然的优化，但把它写进 SQL 会丢掉一个
     * 关键信息：<b>「这个单号已经被之前的批次认领过」</b>。
     * 于是在重传同一份账单时，账单里的每一笔都找不到「在等的」凭证，
     * 全部变成「账单有、系统无」的差异 —— <b>一屏假差异，管理员立刻就不看了</b>。
     *
     * <p>而那个 bug 在单测里测不出来：假 Mapper 会照着实现写，
     * 两边一起错就一起对。只有在真库上跑「同一份账单连对两次」才会暴露。
     *
     * <p>正确做法是让调用方拿到全部候选，在内存里分成三组：
     * <b>可匹配的</b>（未被认领、未被驳回）、<b>已被认领的</b>（只用来判断
     * 「账单里这笔已经对过了」）、<b>被驳回的</b>（只用来报「驳回后有款」）。
     * 分组逻辑在 {@code ReconcileMatcher} 里。
     *
     * <p>⚠️ 窗口两端都是<b>闭区间</b>（{@code >=} / {@code <=}）：
     * 恰好落在边界上的凭证算在内。单测与集成测试各有一条钉着它。
     *
     * @param windowStart 窗口下界（含），由账单最早交易时间减去配置的前置天数算出
     * @param windowEnd   窗口上界（含），由账单最晚交易时间加上配置的后置天数算出
     * @return 候选凭证，按提交时间升序
     */
    @Select("""
            SELECT *
              FROM biz_payment_proof
             WHERE created_at >= #{windowStart}
               AND created_at <= #{windowEnd}
             ORDER BY created_at ASC, id ASC
            """)
    List<PaymentProof> selectCandidatesForReconcile(@Param("windowStart") LocalDateTime windowStart,
                                                    @Param("windowEnd") LocalDateTime windowEnd);

    /**
     * 把一批凭证认领到某个对账批次上。
     *
     * <p><b>⚠️ 只改 {@code reconcile_batch_id} 一列，绝不动 {@code verify_status}。</b>
     * 「认领了顺便确认一下」看起来自然，但那样对账就变成了第二个资金结论入口，
     * 破坏 {@code PaymentProofService} 立下的「复核是唯一结论」这条边界 ——
     * 而凭证一旦被对账悄悄改成「已核对」，管理员就再也分不清
     * 「这笔是我看过截图认下的」还是「系统自己对上的」。集成测试里有一条
     * 专门断言「认领前后 {@code verify_status} 一字不变」。
     *
     * <p><b>带认领守卫</b>（{@code AND reconcile_batch_id IS NULL}）：
     * 两个批次并发跑时，同一笔凭证只会被先到的那个认领走。
     * 调用方拿返回的受影响行数与原计划数一比就知道有没有被抢
     *（对不上就调 {@code ReconcileBatchMapper#updateMatchedCount} 把批次上的计数改回来）。
     *
     * <p>{@code reconcile_batch_id} <b>只写第一次</b>：已经认领过的凭证不会被改写，
     * 于是「这笔是哪一批第一次对出来的」始终查得到。这一条同时让重传幂等。
     *
     * <p>⚠️ {@code proofIds} <b>必须非空</b>：空集合会拼出 {@code IN ()}
     * 这种语法错误。调用方在没有匹配到任何凭证时跳过这次更新。
     *
     * @param batchId  批次 ID
     * @param proofIds 要认领的凭证 ID 列表，非空
     * @return 受影响行数；少于 {@code proofIds} 的长度说明有并发抢占
     */
    @Update("""
            <script>
            UPDATE biz_payment_proof
               SET reconcile_batch_id = #{batchId},
                   updated_at         = NOW()
             WHERE id IN
             <foreach collection="proofIds" item="id" open="(" separator="," close=")">
               #{id}
             </foreach>
               AND reconcile_batch_id IS NULL
            </script>
            """)
    int markReconciled(@Param("batchId") Long batchId, @Param("proofIds") List<Long> proofIds);
}
