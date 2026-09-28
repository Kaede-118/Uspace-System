package com.kaede.uspace.access;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.access.dto.AccessRecordVo;
import com.kaede.uspace.access.dto.ManualOpenRequest;
import com.kaede.uspace.access.dto.RecordOpenVo;
import com.kaede.uspace.access.dto.SyncRecordsVo;
import com.kaede.uspace.access.entity.AccessRecord;
import com.kaede.uspace.access.mapper.AccessRecordMapper;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.lock.LockProperties;
import com.kaede.uspace.lock.LockService;
import com.kaede.uspace.lock.dto.LockRecordDto;
import com.kaede.uspace.lock.dto.LockStatus;
import com.kaede.uspace.space.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 开门记录服务（模块 6）。
 *
 * <p><b>职责：把门锁产生的开门事件拉到本地库里，并提供查询。</b>
 * 本模块是<b>旁路的记录与审计</b>，不参与计费链路 —— 计费起点是订单的
 * {@code start_time}（用户点击「开门」的时刻），由模块 8 自行记录，
 * 不需要等门锁记录回来对齐。
 *
 * <p><b>只记开门，不记离场。</b>封闭空间的出门是机械推杠或按钮（消防要求内部
 * 必须能免密自由开门），<b>不产生门锁记录</b>；离场时刻由用户在应用内点
 * 「结束使用」确定，落在 {@code biz_order.end_time}。所以本表是 append-only 的
 * 事件日志：<b>不提供修改与删除接口</b>。
 *
 * <p><b>为什么要「同步」而不是自动落库</b>：通通锁不提供开门记录的推送回调，
 * 只能主动拉；而免费额度是 30000 次/月。因此同步只在管理员点击时发生一次，
 * <b>绝不做定时轮询</b> —— 哪怕每 10 分钟拉一次，一把锁一个月也要 4300 多次。
 *
 * <p><b>时间区间口径</b>：本类对外一律半开 {@code [from, to)}，与 {@code space} 包一致
 * （{@code 10:00–12:00} 与 {@code 12:00–14:00} 相邻而不重叠）。而
 * {@code LockService#listRecords} 是<b>闭区间</b>，因此调用它时原样传参，
 * 再把结果按半开区间过滤一遍 —— 闭区间最多多给一条恰好落在 {@code to} 上的记录，
 * 丢掉它、留给下一次以 {@code to} 为起点的同步。
 *
 * <p><b>参数错误抛异常，业务失败走返回值</b>：同项目约定。本类里的校验分两种 ——
 * 「调用方给错了数据」（如锁 ID 为空）抛 {@link IllegalArgumentException}，
 * 由全局异常处理器转成 400；「业务规则不接受」（如时间区间颠倒）返回
 * {@link BizResult#fail}，它是可预期的正常分支。
 *
 * <p><b>本类不做的事</b>（划清边界，防止范围蔓延）：不做定时轮询、不记录离场、
 * 不提供记录的修改与删除、不写 {@code biz_order}（跨模块的状态流转由模块 8 负责）。
 */
@Slf4j
@Service
public class AccessRecordService {

    /**
     * 单次同步允许的最大跨度（天）。
     *
     * <p>防止误传一个跨年区间把整年的记录一次拉回来 —— 既慢，又可能超出
     * 门锁云单次返回的上限。分次同步即可，判重会保证不会重复写入。
     */
    private static final int MAX_SYNC_DAYS = 31;

    private final AccessRecordMapper accessRecordMapper;
    private final StoreMapper storeMapper;
    private final LockService lockService;
    private final LockProperties lockProperties;

    /**
     * 构造方法。
     *
     * @param accessRecordMapper 开门记录的数据访问接口
     * @param storeMapper        门店数据访问接口，用于取当前门店
     * @param lockService        门锁服务，同步时从它拉取开门记录
     * @param lockProperties     门锁配置，用于判断记录来源是模拟还是真实门锁云
     */
    public AccessRecordService(AccessRecordMapper accessRecordMapper,
                               StoreMapper storeMapper,
                               LockService lockService,
                               LockProperties lockProperties) {
        this.accessRecordMapper = accessRecordMapper;
        this.storeMapper = storeMapper;
        this.lockService = lockService;
        this.lockProperties = lockProperties;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询开门记录（运营后台）。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param userId   按开门人过滤，传 null 表示不限
     * @param from     时间范围起点（含），传 null 表示不限
     * @param to       时间范围终点（不含），传 null 表示不限
     * @return 成功时返回分页结果；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<PageResult<AccessRecordVo>> listRecords(long pageNum, long pageSize,
                                                             Long userId,
                                                             LocalDateTime from,
                                                             LocalDateTime to) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        IPage<AccessRecord> page = accessRecordMapper.selectPageByStore(
                new Page<>(pageNum, pageSize), storeId, userId, normalize(from), normalize(to));
        return BizResult.ok(PageResult.of(page, AccessRecordVo::from));
    }

    /**
     * 分页查询某用户自己的开门记录（用户端）。
     *
     * <p>走的是 {@code selectPageByUser} 而不是管理端那个方法：后者的 {@code userId}
     * 可空、传 null 就是查全表，用户端绝不能碰。这里的 {@code userId} 由 Controller
     * 从登录凭证里取，路径上不出现用户 ID。
     *
     * @param userId   当前登录用户的 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public BizResult<PageResult<AccessRecordVo>> listMyRecords(Long userId, long pageNum, long pageSize) {
        IPage<AccessRecord> page = accessRecordMapper.selectPageByUser(
                new Page<>(pageNum, pageSize), userId);
        return BizResult.ok(PageResult.of(page, AccessRecordVo::from));
    }

    // ==================================================================
    // 同步
    // ==================================================================

    /**
     * 从门锁云同步开门记录并落库（管理员手动触发）。
     *
     * <p><b>调用额度</b>：正常消耗 1 次门锁云调用；若拉取结果为空，会再探活一次
     * 确认锁是否在线，那就变成 2 次。免费额度 30000 次/月，<b>请勿做成定时轮询</b>。
     *
     * <p><b>为什么拉取为空时还要探活</b>：通通锁的查询接口在失败时<b>返回空列表
     * 而不是抛异常</b>。若直接采信，「云端没拉到」就会被当成「确实没有开门记录」——
     * 系统一声不吭地少记了数据，而这恰恰是最难发现的一类故障。
     * 因此空结果必须再确认一次：锁在线才敢下「确实没有」的结论，
     * 否则返回 {@link ErrorCode#LOCK_CLOUD_UNAVAILABLE} 让管理员重试。
     * 拉取非空时说明云端显然可达，就跳过探活，不白花额度。
     *
     * <p><b>幂等</b>：判重键是 {@code (lock_id, open_time)}。一次同步只发一条查重 SQL
     * （把窗口内已有记录的时刻一次取出来装进 Set），而不是每条记录查一次库。
     * 重复同步同一区间不会写入重复数据，{@code inserted} 会是 0。
     *
     * <p><b>事务的已知折衷</b>：本方法把 {@code listRecords} 这个外部网络调用包在事务里，
     * 会在外部调用期间占用一个数据库连接。本系统单机、手动、低频，可以接受；
     * <b>若将来改成批量或定时同步，必须把拉取挪到事务外</b> —— 且不能靠「抽一个私有方法
     * 加 {@code @Transactional}」来实现（自调用不经过代理，注解不生效），
     * 要另立一个 Bean 或改用 {@code TransactionTemplate}。
     *
     * @param lockId 锁 ID
     * @param from   同步区间起点（含）
     * @param to     同步区间终点（不含）
     * @return 成功时返回同步统计；时间参数不合法、门店不存在或门锁云不可用时返回对应错误码
     * @throws IllegalArgumentException 当锁 ID 或时间区间为空时抛出
     */
    @Transactional
    public BizResult<SyncRecordsVo> syncRecords(Long lockId, LocalDateTime from, LocalDateTime to) {
        if (lockId == null) {
            throw new IllegalArgumentException("锁 ID 不能为空");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("同步区间不能为空");
        }
        if (!to.isAfter(from)) {
            return BizResult.fail(ErrorCode.ACCESS_TIME_INVALID, "同步结束时间必须晚于开始时间");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_SYNC_DAYS) {
            return BizResult.fail(ErrorCode.ACCESS_TIME_INVALID,
                    "单次同步的时间跨度不能超过 " + MAX_SYNC_DAYS + " 天，请分多次同步");
        }

        // 单门店运营下取当前门店。LockRecordDto 不带门店信息，
        // 而 store_id 是 NOT NULL 列 —— 取不到就必须停下，不能落一条 null 进去。
        // 将来开分店时这里要改为按 biz_lock.store_id 反查。
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        LocalDateTime windowStart = normalize(from);
        LocalDateTime windowEnd = normalize(to);

        // 第 1 次门锁云调用：拉取开门记录（上游是闭区间，多给的那条下面会被过滤掉）
        List<LockRecordDto> raw = lockService.listRecords(lockId, windowStart, windowEnd);
        int cloudCalls = 1;

        if (raw.isEmpty()) {
            // 第 2 次调用：只有在「拉取为空」这个无法下结论的分支上才探活
            cloudCalls++;
            LockStatus status = lockService.queryStatus(lockId);
            if (status != LockStatus.ONLINE) {
                log.warn("[进出记录] 同步未完成：拉取结果为空且锁状态为 {}，无法确认「确实没有记录」lockId={}",
                        status, lockId);
                return BizResult.fail(ErrorCode.LOCK_CLOUD_UNAVAILABLE, describeProbeFailure(status));
            }

            log.info("[进出记录] 锁在线且区间内无记录，同步完成 lockId={} 区间 {} ~ {}",
                    lockId, windowStart, windowEnd);
            return BizResult.ok(newSyncVo(lockId, windowStart, windowEnd, 0, 0, 0, 0, cloudCalls));
        }

        // 一次查询取出窗口内已有记录，备好判重用的时刻集合
        Set<LocalDateTime> existing = new HashSet<>();
        for (AccessRecord record : accessRecordMapper.selectInWindow(lockId, windowStart, windowEnd)) {
            existing.add(record.getOpenTime());
        }

        AccessSource source = AccessSource.fromProvider(lockProperties.getProvider());

        int fetched = 0;
        int inserted = 0;
        int skipped = 0;
        int discarded = 0;

        for (LockRecordDto dto : raw) {
            // 截断到秒：门锁云可能给出带亚秒精度的时间，而库列是 DATETIME（秒精度）
            // 且会四舍五入。不归一的话，判重永远比不相等，每次同步都重复插入。
            LocalDateTime openTime = normalize(dto.openTime());
            if (openTime == null) {
                discarded++;
                log.warn("[进出记录] 丢弃一条缺少开门时刻的脏数据 lockId={}", lockId);
                continue;
            }
            // 上游是闭区间 [from, to]，恰好落在 to 上的那条不属于本窗口
            if (openTime.isBefore(windowStart) || !openTime.isBefore(windowEnd)) {
                continue;
            }
            fetched++;

            // Set.add 返回 false 即「该时刻已存在」。同一批里出现两条同秒记录也由它挡住。
            if (!existing.add(openTime)) {
                skipped++;
                continue;
            }

            accessRecordMapper.insert(buildRecord(dto, openTime, storeId, null, source));
            inserted++;
        }

        log.info("[进出记录] 同步完成 lockId={} 区间 {} ~ {} 拉取={} 新增={} 跳过={} 丢弃={} 云端调用={}次",
                lockId, windowStart, windowEnd, fetched, inserted, skipped, discarded, cloudCalls);

        return BizResult.ok(newSyncVo(lockId, windowStart, windowEnd,
                fetched, inserted, skipped, discarded, cloudCalls));
    }

    // ==================================================================
    // 落库
    // ==================================================================

    /**
     * 落库一条开门记录（供模拟开门接口调用）。
     *
     * <p>记录来源由<b>当前启用的门锁实现方</b>决定，不需要调用方传 ——
     * 「这条记录从哪个实现来的」是服务端自己的知识。若让调用方传，
     * 就存在把模拟数据标成真实来源（或反之）的可能。
     *
     * @param dto     门锁返回的开门记录
     * @param orderId 关联订单 ID，可为 null
     * @return 成功时返回记录与「是否新增」标记；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     * @throws IllegalArgumentException 当记录为空或锁 ID 为空时抛出
     */
    @Transactional
    public BizResult<RecordOpenVo> recordOpen(LockRecordDto dto, Long orderId) {
        if (dto == null) {
            throw new IllegalArgumentException("开门记录不能为空");
        }
        return insertIfAbsent(dto, orderId, AccessSource.fromProvider(lockProperties.getProvider()));
    }

    /**
     * 管理员手工补录一条开门记录。
     *
     * <p>使用场景：系统或门锁故障期间开门没能留下记录，事后由管理员按纸质登记
     * 或监控录像补入。
     *
     * <p>来源固定记为 {@link AccessSource#ADMIN}，<b>不由请求方指定</b> ——
     * 「这条是人工补的」是审计上不能抹掉的事实。
     *
     * @param request 补录入参
     * @return 成功时返回记录与「是否新增」标记；开门时刻在未来、门店不存在时返回对应错误码
     */
    @Transactional
    public BizResult<RecordOpenVo> recordManualOpen(ManualOpenRequest request) {
        if (request.getOpenTime().isAfter(LocalDateTime.now())) {
            return BizResult.fail(ErrorCode.ACCESS_TIME_INVALID, "补录的开门时刻不能晚于当前时刻");
        }

        LockRecordDto dto = new LockRecordDto(request.getLockId(), request.getPasscode(),
                request.getOpenTime(), null, request.getUserId());
        return insertIfAbsent(dto, request.getOrderId(), AccessSource.ADMIN);
    }

    /**
     * 落库一条开门记录，同一把锁同一秒已有记录时不再重复写入。
     *
     * <p>同秒判重是有意的：第一次没把门推开、紧接着再输一遍密码，会产生两条
     * 同一秒的记录，但它们描述的是同一次进门，合并成一条更贴近事实、
     * 也避免「同一秒两次开门」这种看着像异常的噪声。
     *
     * <p>本方法是私有的，靠调用方的 {@code @Transactional} 获得事务 ——
     * Spring 的自调用不经过代理，这里再加注解也不会生效。
     *
     * @param dto     门锁返回的开门记录
     * @param orderId 关联订单 ID，可为 null
     * @param source  记录来源
     * @return 落库结果
     */
    private BizResult<RecordOpenVo> insertIfAbsent(LockRecordDto dto, Long orderId, AccessSource source) {
        if (dto.lockId() == null) {
            throw new IllegalArgumentException("锁 ID 不能为空");
        }

        LocalDateTime openTime = normalize(dto.openTime());
        if (openTime == null) {
            return BizResult.fail(ErrorCode.ACCESS_TIME_INVALID, "开门时刻不能为空");
        }

        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        // 窄窗口查询：区间右端开，所以要 +1 秒才能把「这一秒」整个圈进来
        List<AccessRecord> existing = accessRecordMapper.selectInWindow(
                dto.lockId(), openTime, openTime.plusSeconds(1));
        if (!existing.isEmpty()) {
            log.info("[进出记录] 该时刻已有记录，不重复写入 lockId={} 开门时刻={}", dto.lockId(), openTime);
            return BizResult.ok(RecordOpenVo.existing(AccessRecordVo.from(existing.get(0))));
        }

        AccessRecord record = buildRecord(dto, openTime, storeId, orderId, source);
        accessRecordMapper.insert(record);

        log.info("[进出记录] 记录开门 lockId={} 门店={} 用户={} 来源={} 开门时刻={}",
                dto.lockId(), storeId, dto.userId(), source, openTime);

        return BizResult.ok(RecordOpenVo.created(AccessRecordVo.from(record)));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 由门锁返回的记录与已归一的时刻构造实体。
     *
     * @param dto      门锁返回的开门记录
     * @param openTime 已截断到秒的开门时刻
     * @param storeId  门店 ID
     * @param orderId  关联订单 ID，可为 null
     * @param source   记录来源
     * @return 待插入的实体（不含主键与 created_at，两者由框架填充）
     */
    private static AccessRecord buildRecord(LockRecordDto dto, LocalDateTime openTime,
                                            Long storeId, Long orderId, AccessSource source) {
        AccessRecord record = new AccessRecord();
        record.setOrderId(orderId);
        record.setUserId(dto.userId());
        record.setStoreId(storeId);
        record.setLockId(dto.lockId());
        record.setPasscode(dto.keyboardPwd());
        record.setOpenType(dto.openType());
        record.setOpenTime(openTime);
        record.setSource(source.name());
        return record;
    }

    /**
     * 构造同步结果。
     *
     * @param lockId    锁 ID
     * @param from      区间起点
     * @param to        区间终点
     * @param fetched   落在区间内的记录条数
     * @param inserted  实际写入条数
     * @param skipped   因重复跳过的条数
     * @param discarded 因缺少开门时刻丢弃的条数
     * @param cloudCalls 本次消耗的门锁云调用次数
     * @return 同步结果视图
     */
    private static SyncRecordsVo newSyncVo(Long lockId, LocalDateTime from, LocalDateTime to,
                                           int fetched, int inserted, int skipped,
                                           int discarded, int cloudCalls) {
        SyncRecordsVo vo = new SyncRecordsVo();
        vo.setLockId(lockId);
        vo.setFrom(from);
        vo.setTo(to);
        vo.setFetched(fetched);
        vo.setInserted(inserted);
        vo.setSkipped(skipped);
        vo.setDiscarded(discarded);
        vo.setCloudCalls(cloudCalls);
        return vo;
    }

    /**
     * 把时刻截断到秒。
     *
     * <p><b>本模块最要紧的一处细节。</b>门锁云返回的时间可能带亚秒精度，
     * 而 {@code open_time} 列是 {@code DATETIME}（秒精度），MySQL 会对其<b>四舍五入</b>。
     * 若不归一就直接比对，「拉回来的值」与「库里的值」将永远不相等 ——
     * 判重静默失效，每次同步都重复插入，而且<b>不报任何错</b>。
     * 因此落库与判重必须使用同一个归一后的值。
     *
     * @param time 原始时刻，可为 null
     * @return 截断到秒的时刻；入参为 null 时返回 null
     */
    private static LocalDateTime normalize(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * 把探活结果翻译成给管理员看的原因说明。
     *
     * @param status 探活得到的锁状态，调用方已排除 {@link LockStatus#ONLINE}
     * @return 中文说明
     */
    private static String describeProbeFailure(LockStatus status) {
        if (status == LockStatus.OFFLINE) {
            return "门锁当前离线，开门记录可能尚未上报云端，本次同步结果不可信，请稍后重试";
        }
        return "无法确认门锁状态，本次同步结果不可信，请稍后重试";
    }
}
