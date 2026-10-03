package com.kaede.uspace.space;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.dto.StoreStatusVo;
import com.kaede.uspace.space.dto.StoreVo;
import com.kaede.uspace.space.dto.UpdateStoreRequest;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.Closure;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.space.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 门店服务（模块 3）。
 *
 * <p>职责：门店信息的读与改，以及<b>营业状态的判断</b> ——
 * 后者是本模块真正被其他模块用到的地方。
 *
 * <p><b>营业状态不是门店表上的一个字段</b>，而是由另外两张表在「此刻」这个时间点上
 * 推导出来的：落在停业区间内就是停业、落在已付款包场区间内就是包场、
 * 都不是就是正常营业。优先级是<b>停业 &gt; 包场 &gt; 营业</b>：
 * 排期时已校验两者不重叠，真撞上了按更严格的那个算。
 *
 * <p><b>依赖方向</b>：本类依赖 {@link ClosureService} 与 {@link BookingService}，
 * 而这两个服务只依赖各自的 Mapper 与 {@link StoreMapper}，不反过来依赖本类 ——
 * 因此不存在循环依赖。若将来给它们加上「取当前门店信息」的调用，
 * 要立刻警觉这条环。</p>
 */
@Slf4j
@Service
public class StoreService {

    private final StoreMapper storeMapper;
    private final ClosureService closureService;
    private final BookingService bookingService;

    public StoreService(StoreMapper storeMapper,
                        ClosureService closureService,
                        BookingService bookingService) {
        this.storeMapper = storeMapper;
        this.closureService = closureService;
        this.bookingService = bookingService;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 取当前门店信息。
     *
     * <p>单门店运营下即表里唯一的那一条。用户端要展示门店名与地址，
     * 运营后台要在修改前读出当前值，都走这个接口。
     *
     * @return 成功时返回门店信息；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<StoreVo> getCurrentStore() {
        Store store = storeMapper.selectCurrent();
        if (store == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }
        return BizResult.ok(StoreVo.from(store));
    }

    /**
     * 取当前门店 ID。
     *
     * <p><b>为什么要单独暴露一个「取 ID」的方法</b>：计费包（模块 7）的
     * {@code FreePeriodService} 刻意不认识门店 —— 它若注入 {@code StoreMapper}，
     * 就建立了 {@code billing → space} 这条边，而本包的门店页又要反过来调它，
     * 两边一成环就再也拆不开了。所以免费时段的接口由本包提供，
     * 把 ID 递过去（见 {@code AdminStoreController} 的免费时段一组）。
     *
     * <p>与 {@link ClosureService} 内部取门店用的是同一个查询
     * （{@code selectCurrentId}），只是暴露给本包之外调用。
     *
     * @return 门店 ID；门店尚未初始化时返回 null
     */
    public Long getCurrentStoreId() {
        return storeMapper.selectCurrentId();
    }

    /**
     * 取当前营业状态，供用户端首页展示。
     *
     * <p>返回体里同时带上门店信息 —— 用户端首页两样都要，
     * 分成两个接口意味着两次请求，而它们的数据源是同一个「此刻」，
     * 分开查还可能落在不同的时间点上（比如刚好跨过停业开始时刻）。
     *
     * @return 成功时返回营业状态；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<StoreStatusVo> getStoreStatus() {
        Store store = storeMapper.selectCurrent();
        if (store == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        StoreVo storeVo = StoreVo.from(store);
        // 取一次「此刻」并贯穿三次判断，避免边界上出现「查停业时是 9:59、
        // 查包场时已经是 10:00」这类自相矛盾的结果
        LocalDateTime now = LocalDateTime.now();

        Closure closure = closureService.findCoveringClosure(now);
        if (closure != null) {
            return BizResult.ok(StoreStatusVo.closed(storeVo, closure.getEndAt()));
        }

        Booking booking = bookingService.findActiveBookingAt(now);
        if (booking != null) {
            return BizResult.ok(StoreStatusVo.booked(storeVo, booking.getEndAt()));
        }

        return BizResult.ok(StoreStatusVo.open(storeVo));
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    /**
     * 修改门店信息。
     *
     * <p>语义是全量替换（PUT）：字段传 null 即清空该项。
     *
     * <p>名称唯一性在这里查一次以给出友好提示，真正兜底的是库上的唯一索引
     * {@code uk_name} —— 应用层查重挡不住并发，两个管理员同时改名仍可能撞上，
     * 那时由索引拦下并抛 {@code DuplicateKeyException}，全局异常处理器翻译成 409。
     *
     * <p><b>没有「新增门店」与「删除门店」</b>：单门店下这两个动作都没有场景，
     * 记录由建表脚本预置。将来开分店时再加，届时还要一并考虑门店与门锁的绑定。
     *
     * @param request 门店名称、地址与说明
     * @return 成功时返回更新后的门店信息；失败时返回对应错误码
     */
    @Transactional
    public BizResult<StoreVo> updateStore(UpdateStoreRequest request) {
        Store store = storeMapper.selectCurrent();
        if (store == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        String name = request.getName().trim();
        if (storeMapper.countByName(name, store.getId()) > 0) {
            return BizResult.fail(ErrorCode.STORE_NAME_EXISTS);
        }

        storeMapper.updateStore(store.getId(), name,
                trimToNull(request.getAddress()), trimToNull(request.getDescription()));

        log.info("[空间] 修改门店信息 id={} 名称={}", store.getId(), name);

        // 重新查一次而不是改内存对象：updatedAt 由数据库的 NOW() 写入，
        // 手工赋值会与库里的值差几毫秒，返回给前端就成了一个「看起来对但其实是猜的」时间
        return BizResult.ok(StoreVo.from(storeMapper.selectById(store.getId())));
    }

    /**
     * 去除首尾空白，空串归一为 null。
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
