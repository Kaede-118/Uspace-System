package com.kaede.uspace.notice;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.notice.entity.Notice;
import com.kaede.uspace.notice.mapper.NoticeMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存版的 {@link NoticeMapper}，让公告模块与其他用到公告的模块不依赖数据库。
 *
 * <p><b>几处必须与真实 SQL 逐字一致的地方，写错了单测会得出相反结论</b>：
 * <ul>
 *   <li>{@code selectLatest} 只按 {@code id DESC} 排、只取未删的，<b>没有任何时间条件</b> ——
 *       公告是消息流，没有生效/失效时刻</li>
 *   <li>{@code insert} <b>每次都新增一行</b>，不做任何去重 ——
 *       同一台机台反复变化就该产生多条公告</li>
 *   <li>{@code updateManualFields} 带 {@code publish_mode = 'MANUAL'} 条件，
 *       自动公告改不动</li>
 * </ul>
 *
 * <p>其余约定：动态代理、按方法名分发、模拟逻辑删除与自增主键。
 */
public class FakeNoticeMapper implements InvocationHandler {

    /** 模拟数据表 */
    private final Map<Long, Notice> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return NoticeMapper 的假实现
     */
    public NoticeMapper asMapper() {
        return (NoticeMapper) Proxy.newProxyInstance(
                NoticeMapper.class.getClassLoader(),
                new Class<?>[]{NoticeMapper.class},
                this);
    }

    /**
     * 预置一条公告。
     *
     * @param notice 公告，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public Notice seed(Notice notice) {
        if (notice.getId() == null) {
            notice.setId(allocateId());
        }
        if (notice.getDeleted() == null) {
            notice.setDeleted(0);
        }
        rows.put(notice.getId(), notice);
        return notice;
    }

    /**
     * 按 ID 取出当前状态，供测试断言。
     *
     * @param id 公告 ID
     * @return 公告；不存在时返回 null（<b>含已逻辑删除的</b>，方便断言「它被删了」）
     */
    public Notice get(Long id) {
        return rows.get(id);
    }

    /**
     * 当前表里的全部行，含已逻辑删除的。
     *
     * @return 全部公告
     */
    public List<Notice> allRows() {
        return new ArrayList<>(rows.values());
    }

    /**
     * 统计未被逻辑删除的行数。
     *
     * @return 有效公告条数
     */
    public long aliveCount() {
        return rows.values().stream().filter(FakeNoticeMapper::isAlive).count();
    }

    /**
     * 取某台机台产生的全部公告（含多条）。
     *
     * <p>返回的是<b>列表</b>而不是单条 —— 本表是消息流，
     * 同一来源会有很多条，这正是要与「状态投影」区分开的地方。
     *
     * @param sourceType 来源类型
     * @param sourceId   来源 ID
     * @return 匹配的公告，按 id 升序
     */
    public List<Notice> findAllBySource(NoticeSourceType sourceType, Long sourceId) {
        return rows.values().stream()
                .filter(n -> Objects.equals(n.getSourceType(), sourceType.name())
                        && Objects.equals(n.getSourceId(), sourceId))
                .sorted(Comparator.comparing(Notice::getId))
                .toList();
    }

    /**
     * 取最后一条公告，供测试断言「刚刚产生的是哪条」。
     *
     * @return 主键最大的那条；表为空时返回 null
     */
    public Notice last() {
        return rows.values().stream()
                .max(Comparator.comparing(Notice::getId))
                .orElse(null);
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * @return 新 ID
     */
    private long allocateId() {
        while (rows.containsKey(nextId)) {
            nextId++;
        }
        return nextId++;
    }

    /**
     * 方法分发。方法名唯一，所以按名字匹配即可。
     *
     * @param proxy  代理对象（未使用）
     * @param method 被调用的方法
     * @param args   调用参数
     * @return 方法返回值
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "insert" -> insert((Notice) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "deleteById" -> deleteById((Long) args[0]);
            case "selectPageForUser" -> selectPageForUser(args);
            case "selectPageForAdmin" -> selectPageForAdmin(args);
            case "updateManualFields" -> updateManualFields(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeNoticeMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。<b>总是新增一行，不做任何去重</b> —— 与真实 SQL 一致，
     * 因为公告是消息流，同一来源本来就该有多条。
     *
     * <p><b>同时模拟审计字段的自动填充</b>：真实入库时 {@code created_at} /
     * {@code updated_at} 由 MyBatis-Plus 的 {@code AuditMetaObjectHandler} 写入，
     * 而那是框架层的行为，假 Mapper 不走框架。不补这一步的话，
     * 从假 Mapper 读回来的实体 {@code createdAt} 恒为 null ——
     * 而它在真实环境里<b>一定</b>有值（那两列是 NOT NULL）。
     * 断言「前端能拿到发生时刻」的用例会因此假红。
     *
     * @param notice 待插入的公告
     * @return 受影响行数，恒为 1
     */
    private int insert(Notice notice) {
        notice.setId(allocateId());
        if (notice.getDeleted() == null) {
            notice.setDeleted(0);
        }
        LocalDateTime now = LocalDateTime.now();
        if (notice.getCreatedAt() == null) {
            notice.setCreatedAt(now);
        }
        notice.setUpdatedAt(now);
        rows.put(notice.getId(), notice);
        return 1;
    }

    /**
     * 按 ID 查询，过滤已逻辑删除的行。
     *
     * @param id 公告 ID
     * @return 公告；不存在或已删除时返回 null
     */
    private Notice selectById(Long id) {
        Notice notice = rows.get(id);
        return isAlive(notice) ? notice : null;
    }

    /**
     * 逻辑删除。
     *
     * @param id 公告 ID
     * @return 受影响行数；0 表示不存在或已删除
     */
    private int deleteById(Long id) {
        Notice notice = selectById(id);
        if (notice == null) {
            return 0;
        }
        notice.setDeleted(1);
        return 1;
    }

    /**
     * 用户端分页查询：置顶在前、其余按 id 倒序，只取未删的，<b>没有任何时间条件</b>。
     *
     * <p>⚠️ 排序必须与真 SQL 的 {@code ORDER BY pinned DESC, id DESC} 逐字一致 ——
     * 不一致的话，单测与真库会得出相反的结论，而两边都「通过」。
     *
     * @param args 依次为 IPage
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    private IPage<Notice> selectPageForUser(Object[] args) {
        IPage<Notice> page = (IPage<Notice>) args[0];

        List<Notice> matched = rows.values().stream()
                .filter(FakeNoticeMapper::isAlive)
                .sorted(Comparator.comparing(FakeNoticeMapper::isPinned, Comparator.reverseOrder())
                        .thenComparing(Notice::getId, Comparator.reverseOrder()))
                .toList();
        return fillPage(page, matched);
    }

    /** 置顶标记，null 视为不置顶 */
    private static int isPinned(Notice notice) {
        return notice.getPinned() != null && notice.getPinned() == 1 ? 1 : 0;
    }

    /**
     * 后台分页查询。
     *
     * @param args 依次为 IPage、publishMode
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    private IPage<Notice> selectPageForAdmin(Object[] args) {
        IPage<Notice> page = (IPage<Notice>) args[0];
        String publishMode = (String) args[1];

        List<Notice> matched = rows.values().stream()
                .filter(FakeNoticeMapper::isAlive)
                .filter(n -> publishMode == null || publishMode.isEmpty()
                        || publishMode.equals(n.getPublishMode()))
                .sorted(Comparator.comparing(FakeNoticeMapper::isPinned, Comparator.reverseOrder())
                        .thenComparing(Notice::getId, Comparator.reverseOrder()))
                .toList();
        return fillPage(page, matched);
    }

    /**
     * 全量替换手写公告的可编辑字段。
     *
     * <p>带 {@code publish_mode = 'MANUAL'} 条件，与真实 SQL 一致。
     *
     * @param args 依次为 id、title、content、pinned
     * @return 受影响行数；0 表示不存在、已删，或不是手写公告
     */
    private int updateManualFields(Object[] args) {
        Long id = (Long) args[0];
        Notice notice = selectById(id);
        if (notice == null
                || !NoticePublishMode.MANUAL.name().equals(notice.getPublishMode())) {
            return 0;
        }
        notice.setTitle((String) args[1]);
        notice.setContent((String) args[2]);
        notice.setPinned((Integer) args[3]);
        return 1;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param notice 公告，可为 null
     * @return 有效返回 true
     */
    private static boolean isAlive(Notice notice) {
        return notice != null && (notice.getDeleted() == null || notice.getDeleted() == 0);
    }

    /**
     * 把内存里的全部匹配结果按分页参数切片，写回分页对象。
     *
     * @param page 分页参数
     * @param all  满足条件的全部记录
     * @return 同一个分页对象
     */
    private static IPage<Notice> fillPage(IPage<Notice> page, List<Notice> all) {
        page.setTotal(all.size());
        int offset = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        offset = Math.max(offset, 0);
        int end = (int) Math.min(offset + page.getSize(), all.size());
        page.setRecords(new ArrayList<>(all.subList(offset, end)));
        return page;
    }
}
