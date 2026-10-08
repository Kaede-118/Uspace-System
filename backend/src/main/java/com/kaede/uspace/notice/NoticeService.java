package com.kaede.uspace.notice;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.notice.dto.AdminNoticeVo;
import com.kaede.uspace.notice.dto.CreateNoticeRequest;
import com.kaede.uspace.notice.dto.NoticeVo;
import com.kaede.uspace.notice.dto.UpdateNoticeRequest;
import com.kaede.uspace.notice.entity.Notice;
import com.kaede.uspace.notice.event.NoticePublishedEvent;
import com.kaede.uspace.notice.mapper.NoticeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 公告服务。
 *
 * <p><b>公告是一条消息，不是一份状态。</b> 这句话决定了本类的全部行为：
 * <ul>
 *   <li>自动公告<b>只增不改</b>：机台每次状况变化各产生一条，
 *       修好不会把「转为维护中」那条改掉或删掉，而是再产生一条「转为良好」。
 *       首页公告栏因此读起来是一段历史</li>
 *   <li><b>没有撤销、没有失效、没有按来源去重</b>。这些机制都属于
 *       「把公告当成状态的投影」那种设计 —— 在那里，
 *       机台修好之后那条维护公告就<b>必须</b>消失，否则它就在说谎；
 *       而在消息流里，「刚才它确实维护过」是一条永远成立的事实</li>
 *   <li>最新的在最上面，靠主键倒序，不需要置顶与排序权重</li>
 * </ul>
 *
 * <p>面向两类调用方，职责泾渭分明：
 * <ul>
 *   <li><b>用户端与后台</b> —— 走 {@code BizResult}，「业务规则不接受」用失败码表达</li>
 *   <li><b>其他业务模块</b> —— {@link #publishAuto} <b>不返回 BizResult、也不抛异常</b>，
 *       因为它是别人的副作用，失败了不该把别人的主流程拽下来</li>
 * </ul>
 *
 * <p><b>本类不依赖任何业务包</b>：自动公告的文案由调用方取自
 * {@link NoticeContents} 后以字符串传入，本类不认识机台。这是
 * {@code notice} 包能保持「只依赖 common」的前提。
 */
@Slf4j
@Service
public class NoticeService {

    /**
     * 用户端分页每页最多几条。
     *
     * <p>由 {@code UserNoticeController} 的 {@code @Max} 引用，防止调用方
     * 传一个很大的 pageSize 把整表拉出来。改这里就改了接口约束，两处不会是两份数。
     */
    public static final int MAX_USER_PAGE_SIZE = 20;

    private final NoticeMapper noticeMapper;

    /**
     * 发布「公告已发布」事件，由 {@code qqbot} 监听后播报到群。
     *
     * <p>本模块不认识 qqbot，也不该认识 —— 依赖方向是「qqbot → 各业务模块」
     * 单向的（见 {@code NoticePublishedEvent} 的类注释）。
     */
    private final ApplicationEventPublisher eventPublisher;

    public NoticeService(NoticeMapper noticeMapper, ApplicationEventPublisher eventPublisher) {
        this.noticeMapper = noticeMapper;
        this.eventPublisher = eventPublisher;
    }

    // ==================================================================
    // 用户端查询
    // ==================================================================

    /**
     * 分页查询公告，供用户端使用（首页公告栏与「全部公告」页共用这一个接口）。
     *
     * <p>没有可见性判断 —— 公告没有生效/失效时刻，发出来就是可见的。
     *
     * <p><b>为什么是分页而不是「取最近 N 条」</b>：首页那几条看完之后，
     * 用户还能点进「全部公告」一直往下翻。公告是只增不减的消息流
     * （机台每变一次状况就多一条），只给最近若干条的话，更早的内容就永远看不到了。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果，<b>置顶的在前</b>，其余按时间倒序
     */
    public BizResult<PageResult<NoticeVo>> listForUser(long pageNum, long pageSize) {
        IPage<Notice> page = noticeMapper.selectPageForUser(new Page<>(pageNum, pageSize));
        return BizResult.ok(PageResult.of(page, NoticeVo::from));
    }

    /**
     * 后台分页查询公告。
     *
     * @param pageNum     页码，从 1 开始
     * @param pageSize    每页条数
     * @param publishMode 发布方式筛选，可空
     * @return 分页结果
     */
    public BizResult<PageResult<AdminNoticeVo>> listForAdmin(long pageNum, long pageSize,
                                                             String publishMode) {
        IPage<Notice> page = noticeMapper.selectPageForAdmin(
                new Page<>(pageNum, pageSize), trimToNull(publishMode));
        return BizResult.ok(PageResult.of(page, AdminNoticeVo::from));
    }

    // ==================================================================
    // 后台写操作（只针对手写公告）
    // ==================================================================

    /**
     * 发布一条手写公告。
     *
     * <p><b>{@code publishMode} 不由入参决定，方法体内硬编码</b>；
     * {@code sourceType} / {@code sourceId} 也<b>无条件置为 null</b> ——
     * 不是「如果不传就设 null」，而是无论调用方给什么都置空。
     * 这样即使将来请求体加了字段，手写公告也不会挂上来源。
     *
     * @param adminId 发布人（管理员 ID）
     * @param request 标题与正文
     * @return 成功时返回新建的公告
     */
    @Transactional
    public BizResult<AdminNoticeVo> createManual(Long adminId, CreateNoticeRequest request) {
        Notice notice = new Notice();
        notice.setTitle(request.getTitle().trim());
        notice.setContent(trimToNull(request.getContent()));
        notice.setPublishMode(NoticePublishMode.MANUAL.name());
        notice.setSourceType(null);
        notice.setSourceId(null);
        notice.setCreatedBy(adminId);
        notice.setPinned(normalizePinned(request.getPinned()));
        noticeMapper.insert(notice);

        log.info("[公告] 管理员 {} 发布公告 id={} 标题={}", adminId, notice.getId(), notice.getTitle());

        // 手写公告是「运营现在想说的话」，发出来就该让群里也知道一声
        // （自动公告走 publishAuto，发布的是同一个事件）
        eventPublisher.publishEvent(new NoticePublishedEvent(
                notice.getId(), notice.getTitle(), notice.getContent(),
                NoticePublishMode.MANUAL));

        return BizResult.ok(AdminNoticeVo.from(noticeMapper.selectById(notice.getId())));
    }

    /**
     * 修改一条手写公告。
     *
     * <p><b>自动公告改不了</b>，返回 {@link ErrorCode#NOTICE_AUTO_READONLY}。
     * 理由不是权限，而是「改了没有意义」：自动公告是已经发生的事实记录，
     * 改写它等于篡改历史；而如果只是想让它别显示，那也做不到 ——
     * 消息流里没有「下架」这个动作，后面还会有新的消息盖过它。
     *
     * @param id      公告 ID
     * @param request 新的标题与正文
     * @return 成功时返回更新后的公告
     */
    @Transactional
    public BizResult<AdminNoticeVo> updateManual(Long id, UpdateNoticeRequest request) {
        Notice existing = noticeMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.NOTICE_NOT_FOUND);
        }
        if (NoticePublishMode.AUTO.name().equals(existing.getPublishMode())) {
            return BizResult.fail(ErrorCode.NOTICE_AUTO_READONLY);
        }

        // 用显式 SQL 而不是 updateById：MyBatis-Plus 默认的字段策略会跳过 null 字段，
        // 而这里「content 传 null 表示清空正文」是刻意的语义。走 updateById 的话
        // 正文清不掉，而且不报任何错。理由同 DeviceMapper#updateDevice
        noticeMapper.updateManualFields(id, request.getTitle().trim(),
                trimToNull(request.getContent()), normalizePinned(request.getPinned()));

        log.info("[公告] 修改公告 id={} 标题={}", id, request.getTitle().trim());
        return BizResult.ok(AdminNoticeVo.from(noticeMapper.selectById(id)));
    }

    /**
     * 删除一条手写公告（逻辑删除）。
     *
     * <p><b>自动公告删不了</b>，与 {@link #updateManual} 同一条规则 ——
     * 它是已经发生的事实的记录，删掉它等于篡改历史。
     *
     * <p>手写公告可以删，因为它不是「已发生的事实」，而是「运营现在想说的话」——
     * 说错了、过期了、想换个说法，都该能收回来。
     *
     * @param id 公告 ID
     * @return 成功时 data 为 null
     */
    @Transactional
    public BizResult<Void> deleteManual(Long id) {
        Notice existing = noticeMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.NOTICE_NOT_FOUND);
        }
        if (NoticePublishMode.AUTO.name().equals(existing.getPublishMode())) {
            return BizResult.fail(ErrorCode.NOTICE_AUTO_READONLY);
        }

        noticeMapper.deleteById(id);
        log.info("[公告] 删除公告 id={} 标题={}", id, existing.getTitle());
        return BizResult.ok(null);
    }

    // ==================================================================
    // 供其他业务模块调用：记录一条自动公告
    // ==================================================================

    /**
     * 记录一条自动公告（一条事件消息）。<b>总是新增一行</b>。
     *
     * <p>同一台机台反复变化会产生多条公告，这是刻意的 —— 消息流里
     * 「今天修了两次」本身就是值得看见的信息，去重反而会把它抹掉。
     *
     * <p><b>本方法不抛异常、也不返回失败</b>，理由：它总是在别人的事务里被调用
     * （机台改状况）。若让公告写失败把主流程拽下来，代价是
     * 「管理员改不了机台状况，因为公告插不进去」—— 把次要功能的重要性排到了主要功能之前。
     *
     * <p><b>要说清楚它到底保护了什么、没保护什么</b>：
     * <ul>
     *   <li><b>保护</b>：公告自身的意外问题（文案超长、参数缺失）不会影响调用方</li>
     *   <li><b>不保护</b>：它<b>与调用方在同一个事务里</b>。数据库整体不可用时，
     *       调用方本来也成功不了 —— 那种情况下没有「隔离」可言，
     *       而为了隔离去开 {@code REQUIRES_NEW}（另开连接、与主事务争锁）
     *       带来的风险远大于收益</li>
     * </ul>
     *
     * <p>代价是可能出现「机台状态变了但没有公告」这种短暂不一致。
     * 单店场景下它就是一条通知，丢了就丢了，不值得为它引入分布式事务那一套。
     *
     * @param sourceType 来源类型，不可为 null
     * @param sourceId   来源记录 ID，不可为 null
     * @param title      公告标题
     */
    public void publishAuto(NoticeSourceType sourceType, Long sourceId, String title) {
        try {
            // 参数缺失是编程错误，本方法既不抛也不静默 —— 记一条 error 日志，
            // 让它在开发期就显眼地暴露出来
            Objects.requireNonNull(sourceType, "自动公告必须有来源类型");
            Objects.requireNonNull(sourceId, "自动公告必须有来源 ID");

            Notice notice = new Notice();
            notice.setTitle(title);
            // 自动公告不带正文：一条事件通知一句话就够了
            notice.setContent(null);
            notice.setPublishMode(NoticePublishMode.AUTO.name());
            notice.setSourceType(sourceType.name());
            notice.setSourceId(sourceId);
            notice.setCreatedBy(null);
            noticeMapper.insert(notice);

            log.info("[公告] 自动记录 source={}/{} 标题={}", sourceType, sourceId, title);

            // 事件发布放在 try 内、insert 之后：前者是「本方法不抛异常」的契约
            // （见类注释），后者保证「库里有了才播」。有事务时监听器在提交后才跑；
            // 无事务时由监听器的 fallbackExecution 兜住（见 NoticePublishedEvent 注释）
            eventPublisher.publishEvent(new NoticePublishedEvent(
                    notice.getId(), notice.getTitle(), notice.getContent(),
                    NoticePublishMode.AUTO));
        } catch (RuntimeException e) {
            log.error("[公告] 自动记录失败，不影响主流程 source={}/{} 标题={}",
                    sourceType, sourceId, title, e);
        }
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    /**
     * 归一化置顶标记。
     *
     * <p>只认 1，其余（null、0、2、-1）一律按 0。<b>刻意不报错</b>：
     * 置顶是个显示偏好，为它把整个「发公告」的请求打回去不值得。
     *
     * @param pinned 入参，可为 null
     * @return 1 或 0
     */
    private static int normalizePinned(Integer pinned) {
        return pinned != null && pinned == 1 ? 1 : 0;
    }

    /**
     * 去除首尾空白，空串归一为 null。
     *
     * <p>统一成 null 而不是空串，是为了让「没填」在库里只有一种表示 ——
     * 理由同 {@code DeviceService#trimToNull}。
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
