package com.kaede.uspace.space;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.space.dto.ClosureRequest;
import com.kaede.uspace.space.dto.ClosureVo;
import com.kaede.uspace.space.entity.Closure;
import com.kaede.uspace.space.event.ClosureChangeAction;
import com.kaede.uspace.space.event.ClosureChangedEvent;
import com.kaede.uspace.space.mapper.ClosureMapper;
import com.kaede.uspace.space.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 停业记录服务（模块 3）。
 *
 * <p>职责：停业时段的增删改查，以及对外提供「某时刻是否停业」的判断。
 *
 * <p><b>为什么要单独一张表而不是门店上的一个状态位</b>：状态位只表达
 * 「此刻开 / 此刻关」，而运营需要的是「明天 10:00–14:00 维护」这种<b>提前安排</b>。
 * 做成时段记录后，到点自动生效，无需人工盯守。
 *
 * <p><b>本类不注入 {@link StoreService}</b>：门店服务要反过来依赖本类来判断营业状态，
 * 若本类也依赖它就成了循环。因此这里直接用
 * {@link StoreMapper#selectCurrentId()} 取当前门店 —— 单门店下就是唯一那条。
 *
 * <p><b>参数错误抛异常，业务失败走返回值</b>：同 {@code UserService} 的约定。
 * 本类里所有校验失败都是「业务规则不接受」，因此一律走
 * {@link BizResult#fail}，不抛异常。
 */
@Slf4j
@Service
public class ClosureService {

    private final ClosureMapper closureMapper;
    private final StoreMapper storeMapper;

    /**
     * 发布「停业时段变更」事件，由 {@code qqbot} 监听后播报到群。
     *
     * <p>新增与撤销<b>都播</b>：群里看到过「明天 10:00 不营业」之后，
     * 撤销若不播，那条消息就永远停留在群里，成为一条过期的假消息。
     */
    private final ApplicationEventPublisher eventPublisher;

    public ClosureService(ClosureMapper closureMapper, StoreMapper storeMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.closureMapper = closureMapper;
        this.storeMapper = storeMapper;
        this.eventPublisher = eventPublisher;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询停业记录。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 成功时返回分页结果；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<PageResult<ClosureVo>> listClosures(long pageNum, long pageSize) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        IPage<Closure> page = closureMapper.selectPageByStore(new Page<>(pageNum, pageSize), storeId);
        return BizResult.ok(PageResult.of(page, ClosureVo::from));
    }

    /**
     * 判断当前门店在某时刻是否处于停业状态。
     *
     * <p><b>这是本类对外提供的准入判断入口</b>，供模块 8 的下单链路调用 ——
     * 返回 {@code true} 时应当拒绝新订单。刻意返回 {@code boolean}
     * 而不是 {@code BizResult}：调用方只需要一个「能不能」的答案，
     * 不需要知道停业原因（那是运营内部信息）。
     *
     * <p>门店不存在时返回 {@code false}（不停业）—— 那属于系统未初始化，
     * 下单链路会因为找不到门店而自行失败，不该在这里伪装成「停业」，
     * 否则排障时会被误导到错误的方向。
     *
     * @param time 待判断的时刻
     * @return 停业中返回 true
     */
    public boolean isClosedAt(LocalDateTime time) {
        return findCoveringClosure(time) != null;
    }

    /**
     * 查询覆盖某时刻的停业记录。
     *
     * <p>与 {@link #isClosedAt} 的区别是它把整条记录交出来 ——
     * 用户端要知道「几点恢复营业」，需要的是停业的结束时刻，
     * 一个 boolean 给不了。两个方法并存，是让只关心「能不能进」的调用方
     * 用前者（意图更清晰），需要细节的用后者。
     *
     * @param time 待判断的时刻
     * @return 覆盖该时刻的停业记录；没有则返回 null
     */
    public Closure findCoveringClosure(LocalDateTime time) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return null;
        }
        return closureMapper.selectCoveringAt(storeId, time);
    }

    /**
     * 判断某时段是否与既有停业记录重叠。
     *
     * <p>供 {@link BookingService} 在排包场时校验 —— 包场与停业都是运营安排的，
     * 两者撞在同一时段属于排期失误：顾客付了钱包场，到店却发现大门锁着。
     *
     * @param startAt 时段开始时刻
     * @param endAt   时段结束时刻
     * @return 重叠返回 true；门店不存在时返回 false
     */
    public boolean overlapsClosure(LocalDateTime startAt, LocalDateTime endAt) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return false;
        }
        return closureMapper.countOverlapping(storeId, startAt, endAt, null) > 0;
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    /**
     * 新增停业记录。
     *
     * <p><b>允许登记过去的时间</b>：管理员可能在事后补录
     * （如「昨天临时停业了半天，补一下」），拒绝历史时刻会让这件事做不了。
     *
     * <p>与既有停业时段重叠会被拒绝 —— 重叠本身不致命（都是「不营业」），
     * 但会让后台列表出现含义重复的记录，运营看到两份「为什么停业」也不知道信哪个。
     *
     * @param request 停业时段与原因
     * @param adminId 登记人（当前登录的管理员 ID）
     * @return 成功时返回新建的记录；失败时返回对应错误码
     */
    @Transactional
    public BizResult<ClosureVo> createClosure(ClosureRequest request, Long adminId) {
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            return BizResult.fail(ErrorCode.CLOSURE_TIME_INVALID);
        }

        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        int overlapping = closureMapper.countOverlapping(
                storeId, request.getStartAt(), request.getEndAt(), null);
        if (overlapping > 0) {
            return BizResult.fail(ErrorCode.CLOSURE_OVERLAP);
        }

        Closure closure = new Closure();
        closure.setStoreId(storeId);
        closure.setStartAt(request.getStartAt());
        closure.setEndAt(request.getEndAt());
        closure.setReason(trimToNull(request.getReason()));
        closure.setCreatedBy(adminId);
        closureMapper.insert(closure);

        log.info("[空间] 新增停业记录 id={} 时段 {} ~ {} 原因={}",
                closure.getId(), closure.getStartAt(), closure.getEndAt(), closure.getReason());
        publishChanged(closure, ClosureChangeAction.CREATED);
        return BizResult.ok(ClosureVo.from(closure));
    }

    /**
     * 修改停业记录。
     *
     * <p>与新增的两点不同：一是校验重叠时要排除自己（否则改成一个
     * 与原时段有交集的时间就会被自己挡住）；二是记录必须已存在。
     *
     * @param id      记录 ID
     * @param request 新的停业时段与原因
     * @return 成功时 data 为 null；失败时返回对应错误码
     */
    @Transactional
    public BizResult<Void> updateClosure(Long id, ClosureRequest request) {
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            return BizResult.fail(ErrorCode.CLOSURE_TIME_INVALID);
        }

        Closure existing = closureMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.CLOSURE_NOT_FOUND);
        }

        int overlapping = closureMapper.countOverlapping(
                existing.getStoreId(), request.getStartAt(), request.getEndAt(), id);
        if (overlapping > 0) {
            return BizResult.fail(ErrorCode.CLOSURE_OVERLAP);
        }

        closureMapper.updateClosure(id, request.getStartAt(), request.getEndAt(),
                trimToNull(request.getReason()));

        log.info("[空间] 修改停业记录 id={} 新时段 {} ~ {}", id, request.getStartAt(), request.getEndAt());
        return BizResult.ok(null);
    }

    /**
     * 删除停业记录（逻辑删除）。
     *
     * <p>用逻辑删除而非物理删除，与全库约定一致 —— {@code deleted = 1} 之后
     * 该记录不再参与停业判断，但仍在库里留着痕迹，
     * 事后复盘「那天到底为什么关门」时查得到。
     *
     * @param id 记录 ID
     * @return 成功时 data 为 null；记录不存在时返回 {@link ErrorCode#CLOSURE_NOT_FOUND}
     */
    @Transactional
    public BizResult<Void> deleteClosure(Long id) {
        Closure existing = closureMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.CLOSURE_NOT_FOUND);
        }

        // removeById 会被逻辑删除配置改写成 UPDATE ... SET deleted = 1
        closureMapper.deleteById(id);

        log.info("[空间] 删除停业记录 id={} 原时段 {} ~ {}",
                id, existing.getStartAt(), existing.getEndAt());
        // 撤销播报要用删除前查到的那一份 —— 删完就查不到了。
        // 上面那次 selectById 本来就有，直接用它，不必再查一次
        publishChanged(existing, ClosureChangeAction.DELETED);
        return BizResult.ok(null);
    }

    /**
     * 发布「停业时段变更」事件（{@code qqbot} 监听后播报到群）。
     *
     * <p><b>异常自己吞掉。</b>播报是次要功能，一次发布失败不该让停业安排
     * 保存不了 —— 管理员排的是「明天不开门」这件事本身，播报只是附带的一句通知。
     *
     * @param closure 停业记录；撤销时传的是删除前查到的那一份
     * @param action  新增还是撤销
     */
    private void publishChanged(Closure closure, ClosureChangeAction action) {
        try {
            eventPublisher.publishEvent(new ClosureChangedEvent(
                    closure.getId(), closure.getStartAt(), closure.getEndAt(),
                    closure.getReason(), action));
        } catch (RuntimeException e) {
            log.warn("[空间] 发布停业变更事件失败，跳过群播报 closureId={} action={}",
                    closure.getId(), action, e);
        }
    }

    /**
     * 去除首尾空白，空串归一为 null。
     *
     * <p>统一成 null 而不是空串，是为了让「没填」在库里只有一种表示 ——
     * 否则 {@code ''} 与 {@code NULL} 混用，查「没有原因的停业」时得写两个条件。
     *
     * @param value 原始字符串，可为 null
     * @return 去空白后的字符串；空白串返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
