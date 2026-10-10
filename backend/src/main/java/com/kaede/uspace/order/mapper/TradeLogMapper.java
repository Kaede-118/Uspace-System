package com.kaede.uspace.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kaede.uspace.order.entity.TradeLog;

/**
 * 交易流水的数据访问接口。
 *
 * <p><b>当前只需要 MyBatis-Plus 自带的 {@code insert}</b>：本表是纯写入的留档，
 * 还没有任何页面按条件查它。将来做「流水查询」页时再往这里加查询方法，
 * 那时第一个要考虑的是分页口径（一张 append-only 的表只会越长越长）。
 *
 * <p>⚠️ <b>本表没有 {@code updated_at} 与 {@code deleted} 两列</b>
 * （append-only，见实体的类注释）。将来加手写 SQL 时
 * <b>一个 {@code deleted = 0} 都不要写</b> —— 照抄别的 Mapper 会直接
 * {@code Unknown column}，而这个错误只会在跑到那条 SQL 时才出现。
 * 与 {@code PaymentProofMapper}、{@code AccessRecordMapper} 是同一条注意。
 */
public interface TradeLogMapper extends BaseMapper<TradeLog> {
}
