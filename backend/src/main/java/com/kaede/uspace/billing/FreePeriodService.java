package com.kaede.uspace.billing;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.billing.dto.FreePeriodRequest;
import com.kaede.uspace.billing.dto.FreePeriodVo;
import com.kaede.uspace.billing.entity.FreePeriod;
import com.kaede.uspace.billing.mapper.FreePeriodMapper;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 免费时段（活动）服务（模块 7）。
 *
 * <p>职责：活动的增删改查，以及对外提供「某个区间的免费时段」给计费侧用。
 *
 * <p>⚠️ <b>它是计费规则，不是准入规则</b>：与停业（{@code ClosureService}）
 * 形态极像但作用完全不同 —— 停业拒绝新订单，本表只是让账单算 0。
 * 店里照常营业、人照进、门照开。
 *
 * <p>⚠️ <b>本类不认识「门店」</b>：所有方法都收 {@code storeId} 参数，由调用方给。
 * 这样做是为了让依赖方向保持单向 —— 本包若去注入 {@code StoreMapper}，
 * 就建立了 {@code billing → space} 这条边，而 {@code space}（后台的门店页）
 * 又要反过来调本类，两边一成环就再也拆不开了。
 * 计费规则本身也确实不需要知道门店是谁：「哪些时刻之间不收钱」就够了。
 *
 * <p><b>参数错误抛异常，业务失败走返回值</b>：同 {@code ClosureService} 的约定。
 */
@Slf4j
@Service
public class FreePeriodService {

    /** 用户端「近期活动」默认返回几条 */
    private static final int DEFAULT_UPCOMING_LIMIT = 3;

    /** 用户端「近期活动」最多返回几条 */
    private static final int MAX_UPCOMING_LIMIT = 20;

    private final FreePeriodMapper freePeriodMapper;

    public FreePeriodService(FreePeriodMapper freePeriodMapper) {
        this.freePeriodMapper = freePeriodMapper;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询本门店的活动（后台用）。
     *
     * @param storeId  门店 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public BizResult<PageResult<FreePeriodVo>> listPeriods(Long storeId, long pageNum, long pageSize) {
        IPage<FreePeriod> page = freePeriodMapper.selectPageByStore(
                new Page<>(pageNum, pageSize), storeId);
        return BizResult.ok(PageResult.of(page, FreePeriodVo::from));
    }

    /**
     * 查尚未结束的活动（用户端「近期免费活动」用）。
     *
     * <p>正在进行的也算 —— 「今晚 20:00–次日 02:00 免费」这条在 21:00 打开首页时
     * 正是最该看见的。
     *
     * @param storeId 门店 ID
     * @param from    起始时刻（通常是「现在」）
     * @param limit   最多返回几条；为 null 或越界时取边界值（见 {@link #normalizeUpcomingLimit}）
     * @return 活动列表，按开始时间升序；没有时返回空列表
     */
    public List<FreePeriodVo> listUpcoming(Long storeId, LocalDateTime from, Integer limit) {
        if (storeId == null || from == null) {
            return List.of();
        }
        return freePeriodMapper.selectUpcoming(storeId, from, normalizeUpcomingLimit(limit)).stream()
                .map(FreePeriodVo::from)
                .toList();
    }

    /**
     * 取与给定区间有交集的活动区间（<b>计费侧唯一入口</b>）。
     *
     * <p>返回的是<b>活动本身的区间</b>，不裁剪到订单区间 —— 裁剪由
     * {@code BillingService} 的切段逻辑做（它才是边界口径的定义处）。
     * 在这里先裁的话，同一件事就有两处说法，而两处都「看起来合理」。
     *
     * <p>⚠️ <b>坏数据在这里过滤掉并记 warn，不让它把计费拖崩</b>：
     * 库里若有一条 {@code start_at >= end_at} 的记录（手工改过库、或将来某条
     * 写入路径漏了校验），{@link FreeRange} 的构造器会抛异常 ——
     * 那会让**所有订单都结算不了**，代价远大于「少免一次活动」。
     * 过滤掉至少账单能算出来，而 warn 日志把问题留下了痕迹。
     *
     * @param storeId 门店 ID
     * @param from    订单计费区间起点
     * @param to      订单计费区间终点
     * @return 免费区间列表；没有活动时返回空列表（调用方不必判 null）
     */
    public List<FreeRange> findRangesFor(Long storeId, LocalDateTime from, LocalDateTime to) {
        if (storeId == null || from == null || to == null || !to.isAfter(from)) {
            return List.of();
        }
        List<FreeRange> ranges = new ArrayList<>();
        for (FreePeriod period : freePeriodMapper.selectOverlapping(storeId, from, to)) {
            try {
                ranges.add(new FreeRange(period.getStartAt(), period.getEndAt()));
            } catch (IllegalArgumentException e) {
                log.warn("[免费活动] 跳过一条起止不合法的记录 id={}：{}", period.getId(), e.getMessage());
            }
        }
        return ranges;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    /**
     * 新增活动。
     *
     * <p><b>允许登记过去的时间</b>：与停业同理，管理员可能事后补录
     *（如「昨天的活动忘了排，补一下」）—— 拒绝历史时刻会让这件事做不了。
     *
     * <p>与既有活动重叠会被拒绝：重叠本身不致命（都是免费），但会让后台出现
     * 含义重复的记录，计费侧还要多切一刀（多切一段就多享一次宽限）。
     *
     * @param storeId 门店 ID
     * @param request 活动时段与名称
     * @param adminId 登记人（当前登录的管理员 ID）
     * @return 成功时返回新建的记录；失败时返回对应错误码
     */
    @Transactional
    public BizResult<FreePeriodVo> createPeriod(Long storeId, FreePeriodRequest request, Long adminId) {
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            return BizResult.fail(ErrorCode.FREE_PERIOD_TIME_INVALID);
        }
        if (freePeriodMapper.countOverlapping(storeId, request.getStartAt(),
                request.getEndAt(), null) > 0) {
            return BizResult.fail(ErrorCode.FREE_PERIOD_OVERLAP);
        }

        FreePeriod period = new FreePeriod();
        period.setStoreId(storeId);
        period.setStartAt(request.getStartAt());
        period.setEndAt(request.getEndAt());
        // reason 为空串时统一存 null —— 空串与 null 在库里的含义一样，
        // 存两种形态只会让「有没有填名称」这个判断要看两个条件
        period.setReason(trimToNull(request.getReason()));
        period.setCreatedBy(adminId);
        freePeriodMapper.insert(period);

        log.info("[免费活动] 新增 id={}，{} ~ {}，原因={}（登记人 {}）",
                period.getId(), period.getStartAt(), period.getEndAt(),
                period.getReason(), adminId);
        return BizResult.ok(FreePeriodVo.from(period));
    }

    /**
     * 修改活动。
     *
     * <p>重叠校验要<b>排除自己</b>（{@code excludeId}）—— 否则「只改个名字」
     * 会因为与自己重叠而被拒。
     *
     * @param storeId 门店 ID
     * @param id      活动 ID
     * @param request 新的时段与名称
     * @return 成功时返回更新后的记录；失败时返回对应错误码
     */
    @Transactional
    public BizResult<FreePeriodVo> updatePeriod(Long storeId, Long id, FreePeriodRequest request) {
        FreePeriod existing = findOwned(storeId, id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.FREE_PERIOD_NOT_FOUND);
        }
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            return BizResult.fail(ErrorCode.FREE_PERIOD_TIME_INVALID);
        }
        if (freePeriodMapper.countOverlapping(storeId, request.getStartAt(),
                request.getEndAt(), id) > 0) {
            return BizResult.fail(ErrorCode.FREE_PERIOD_OVERLAP);
        }

        // 走显式 SQL 而不是 updateById：后者的「跳过 null 字段」语义会让
        // 「把活动名称清空」做不到，且不报任何错（见 FreePeriodMapper#updatePeriod）
        freePeriodMapper.updatePeriod(id, request.getStartAt(), request.getEndAt(),
                trimToNull(request.getReason()));

        // 重新查一次而不是拿 update 对象返回：updateById 只更新非 null 字段，
        // 而 update 对象上 storeId / createdBy / createdAt 都是 null
        return BizResult.ok(FreePeriodVo.from(findOwned(storeId, id)));
    }

    /**
     * 删除活动（逻辑删除）。
     *
     * <p>删除之后计费立刻不再免 —— 与停业同理，这是运营想要的效果
     *（「活动取消了」）。
     *
     * @param storeId 门店 ID
     * @param id      活动 ID
     * @return 成功无数据；活动不存在时返回 {@link ErrorCode#FREE_PERIOD_NOT_FOUND}
     */
    @Transactional
    public BizResult<Void> deletePeriod(Long storeId, Long id) {
        if (findOwned(storeId, id) == null) {
            return BizResult.fail(ErrorCode.FREE_PERIOD_NOT_FOUND);
        }
        freePeriodMapper.deleteById(id);
        log.info("[免费活动] 删除 id={}", id);
        return BizResult.ok(null);
    }

    // ==================================================================
    // 私有工具
    // ==================================================================

    /**
     * 按 ID 查活动，并确认它属于本门店。
     *
     * <p>不属于本门店时返回 null（当作不存在）—— 与 {@code ClosureService}
     * 同一取向：单门店下这条判断不会触发，但多门店时它是「A 店的管理员改了 B 店的活动」
     * 的唯一防线，而那种错从界面上看不出来。
     *
     * @param storeId 门店 ID
     * @param id      活动 ID
     * @return 活动；不存在或不属于本门店时返回 null
     */
    private FreePeriod findOwned(Long storeId, Long id) {
        FreePeriod period = freePeriodMapper.selectById(id);
        if (period == null || !period.getStoreId().equals(storeId)) {
            return null;
        }
        return period;
    }

    /**
     * 规范化「近期活动」的条数上限。
     *
     * <p>与 {@code BookingService} 的包场时间表同一取向：<b>越界不报错，截到边界值</b> ——
     * 前端传错一个数不该让整个首页 500，少显示几条比看不到好。
     *
     * @param limit 原始值，可为 null
     * @return 落在 [1, 20] 内的条数
     */
    private static int normalizeUpcomingLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_UPCOMING_LIMIT;
        }
        return Math.min(limit, MAX_UPCOMING_LIMIT);
    }

    /**
     * 空白字符串归一成 null。与 {@code ClosureService#trimToNull} 同一份逻辑。
     *
     * @param value 原值
     * @return 去掉首尾空白；结果为空时返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
