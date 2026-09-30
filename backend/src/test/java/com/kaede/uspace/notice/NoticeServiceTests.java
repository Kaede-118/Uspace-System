package com.kaede.uspace.notice;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.notice.dto.AdminNoticeVo;
import com.kaede.uspace.notice.dto.CreateNoticeRequest;
import com.kaede.uspace.notice.dto.NoticeVo;
import com.kaede.uspace.notice.dto.UpdateNoticeRequest;
import com.kaede.uspace.notice.entity.Notice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NoticeService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>数据访问由 {@link FakeNoticeMapper} 顶替。
 *
 * <p><b>本类盯的核心是一句话：公告是消息流，不是状态快照。</b>
 * 由它推出三条容易被写反的行为：
 * <ol>
 *   <li><b>同一来源可以有很多条</b> —— 机台每次状况变化各产生一条。
 *       若哪天有人给它加回去重（因为觉得「一条就够了」），
 *       表现是「今天修了两次」只能看见一次，而不会报任何错</li>
 *   <li><b>没有撤销</b> —— 机台修好不会把「转为维护中」那条删掉或改掉。
 *       消息流里没有「撤销已经发生的事」这回事</li>
 *   <li><b>最新的在最上面</b>，靠主键倒序，没有置顶与权重</li>
 * </ol>
 */
class NoticeServiceTests {

    /** 内存版数据访问层 */
    private final FakeNoticeMapper noticeMapper = new FakeNoticeMapper();

    /** 被测服务 */
    private final NoticeService noticeService = new NoticeService(noticeMapper.asMapper());

    // ==================================================================
    // 消息流语义：只增不改
    // ==================================================================

    @Test
    @DisplayName("消息流：同一来源发两次就是两条（不做去重）")
    void publishAuto_sameSourceTwice_createsTwoRows() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 42L, "拍拍机 1 号 由 良好 转为 维护中");
        noticeService.publishAuto(NoticeSourceType.DEVICE, 42L, "拍拍机 1 号 由 维护中 转为 良好");

        assertEquals(2, noticeMapper.aliveCount(),
                "同一台机台先坏后好，是两件事、两条消息。"
                        + "去重会把「今天修过」这段历史抹掉，而且不会报任何错");
    }

    @Test
    @DisplayName("消息流：最新的排在最前面")
    void listForUser_newestFirst() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "第一条");
        noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "第二条");
        noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "第三条");

        List<NoticeVo> notices = noticeService.listForUser(1, 5).getData().getRecords();

        assertEquals(List.of("第三条", "第二条", "第一条"),
                notices.stream().map(NoticeVo::getTitle).toList(),
                "首页是一段倒序的时间线");
    }

    @Test
    @DisplayName("消息流：一条消息发出来就可见，没有生效/失效时刻")
    void listForUser_hasNoTimeWindow() {
        Notice seeded = noticeMapper.seed(auto("很久以前的一条"));
        seeded.setCreatedAt(LocalDateTime.now().minusYears(1));

        assertEquals(1, noticeService.listForUser(1, 5).getData().getRecords().size(),
                "公告没有「有效期」—— 一条消息不会到点自己消失。"
                        + "首页只展示最近几条，更早的留在表里");
    }

    @Test
    @DisplayName("消息流：自动公告不带正文，只有一句话")
    void publishAuto_hasNoContent() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "拍拍机 1 号 由 良好 转为 维护中");

        assertNull(noticeMapper.last().getContent(),
                "一条事件通知一句话就够了，正文是留给管理员手写公告的");
    }

    @Test
    @DisplayName("消息流：自动公告来源为 null 时不写入，也不抛异常打断主流程")
    void publishAuto_nullSource_doesNotThrow() {
        // 这是编程错误，但必须「不影响主流程」—— 机台改状况不能因为公告写失败而失败
        noticeService.publishAuto(NoticeSourceType.DEVICE, null, "标题");

        assertEquals(0, noticeMapper.aliveCount(), "参数不全时不该写入任何东西");
    }

    @Test
    @DisplayName("消息流：分页生效，第二页接着第一页往下翻")
    void listForUser_pages() {
        for (int i = 0; i < 12; i++) {
            noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "消息 " + i);
        }

        List<NoticeVo> first = noticeService.listForUser(1, 5).getData().getRecords();
        List<NoticeVo> second = noticeService.listForUser(2, 5).getData().getRecords();

        assertEquals(5, first.size(), "首页那几条");
        assertEquals(5, second.size(), "第二页接着往下");
        assertEquals(12, noticeService.listForUser(1, 5).getData().getTotal());
        // 第一页最后一条是「消息 7」（12 条里倒序第 5 条），第二页第一条应当是「消息 6」
        assertEquals("消息 7", first.get(4).getTitle());
        assertEquals("消息 6", second.get(0).getTitle(), "两页之间不能有重叠或跳条");
    }

    @Test
    @DisplayName("置顶：钉住的排在最前，其余仍按时间倒序")
    void listForUser_pinnedFirst() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "最先发生的");
        noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "后来发生的");

        // 钉住最早那条
        Notice pinned = noticeMapper.seed(manual("请务必看到这条", null));
        pinned.setPinned(1);

        List<NoticeVo> notices = noticeService.listForUser(1, 5).getData().getRecords();

        assertEquals("请务必看到这条", notices.get(0).getTitle(),
                "置顶的排在最前 —— 首页前几条就那么点位置，"
                        + "「今天临时调整营业时间」这类消息必须挤得进去");
        assertTrue(notices.get(0).getPinned(), "视图里要带上置顶标记，前端才好画个角标");
        assertEquals("后来发生的", notices.get(1).getTitle(), "其余仍按时间倒序");
    }

    @Test
    @DisplayName("置顶：只有手写公告钉得住，自动公告恒为不置顶")
    void pinned_isAlwaysFalseForAutoNotices() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "机台变了一下");

        assertFalse(noticeMapper.last().getPinned() != null && noticeMapper.last().getPinned() == 1,
                "置顶是运营的意图，自动公告是系统记录 —— 让机台故障能占满首页前几条是说不过去的");
    }

    @Test
    @DisplayName("对外视图：带时间、带发布方式，但不含来源与发布人")
    void listForUser_exposesOnlySafeFields() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 42L, "拍拍机 1 号 由 良好 转为 维护中");

        NoticeVo vo = noticeService.listForUser(1, 5).getData().getRecords().get(0);

        assertEquals("拍拍机 1 号 由 良好 转为 维护中", vo.getTitle());
        assertEquals(NoticePublishMode.AUTO.name(), vo.getPublishMode());
        assertEquals("系统", vo.getPublishModeText());
        assertNotNull(vo.getCreatedAt(), "有发生时刻，前端才能显示「3 分钟前」");
        // NoticeVo 里根本没有 sourceId / sourceType / createdBy 这三个字段 ——
        // 白名单 VO 的意义就在这里：想泄露也拿不到
        assertEquals(42L, noticeMapper.last().getSourceId(), "来源 ID 只在库里");
    }

    // ==================================================================
    // 手写公告
    // ==================================================================

    @Test
    @DisplayName("手写公告：发布时硬编码为 MANUAL，且来源两列置空")
    void createManual_forcesManualModeAndNullSource() {
        BizResult<AdminNoticeVo> result = noticeService.createManual(99L, request("本周六场地维护"));

        assertTrue(result.isSuccess());
        AdminNoticeVo vo = result.getData();
        assertEquals(NoticePublishMode.MANUAL.name(), vo.getPublishMode());
        assertEquals("手写", vo.getPublishModeText());
        assertNull(vo.getSourceType(), "手写公告不该有来源 —— ck_notice_source 会挡住");
        assertNull(vo.getSourceId());
        assertEquals(99L, vo.getCreatedBy(), "记下是谁发的");
        assertTrue(vo.isEditable());
    }

    @Test
    @DisplayName("手写公告：可以修改，正文传 null 表示清空")
    void updateManual_canClearContent() {
        Notice seeded = noticeMapper.seed(manual("原标题", "原正文"));

        UpdateNoticeRequest request = new UpdateNoticeRequest();
        request.setTitle("新标题");
        request.setContent(null);

        BizResult<AdminNoticeVo> result = noticeService.updateManual(seeded.getId(), request);

        assertTrue(result.isSuccess());
        assertEquals("新标题", noticeMapper.get(seeded.getId()).getTitle());
        assertNull(noticeMapper.get(seeded.getId()).getContent(),
                "传 null 是清空正文，不是「不修改」—— 用 updateById 的话这个动作做不到且不报错");
    }

    @Test
    @DisplayName("手写公告：可以删除，删掉之后谁也查不到了")
    void deleteManual_softDeletes() {
        Notice seeded = noticeMapper.seed(manual("要删掉的通知", null));

        BizResult<Void> result = noticeService.deleteManual(seeded.getId());

        assertTrue(result.isSuccess());
        assertEquals(1, noticeMapper.get(seeded.getId()).getDeleted(), "走逻辑删除，不是物理删除");
        assertTrue(noticeService.listForUser(1, 5).getData().getRecords().isEmpty());
    }

    @Test
    @DisplayName("手写公告：不存在时返回 NOTICE_NOT_FOUND")
    void updateManual_notFound() {
        UpdateNoticeRequest request = new UpdateNoticeRequest();
        request.setTitle("随便");

        assertEquals(ErrorCode.NOTICE_NOT_FOUND, noticeService.updateManual(404L, request).getError());
        assertEquals(ErrorCode.NOTICE_NOT_FOUND, noticeService.deleteManual(404L).getError());
    }

    // ==================================================================
    // 自动公告只读
    // ==================================================================

    @Test
    @DisplayName("自动公告：改不了，返回 NOTICE_AUTO_READONLY")
    void updateManual_autoNoticeIsReadOnly() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 42L, "系统写的消息");
        Long id = noticeMapper.last().getId();

        UpdateNoticeRequest request = new UpdateNoticeRequest();
        request.setTitle("我想改成别的");

        BizResult<AdminNoticeVo> result = noticeService.updateManual(id, request);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.NOTICE_AUTO_READONLY, result.getError());
        assertEquals("系统写的消息", noticeMapper.get(id).getTitle(),
                "它是已经发生的事实的记录，改写等于篡改历史");
    }

    @Test
    @DisplayName("自动公告：删不掉，返回 NOTICE_AUTO_READONLY")
    void deleteManual_autoNoticeIsReadOnly() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 42L, "系统写的消息");
        Long id = noticeMapper.last().getId();

        BizResult<Void> result = noticeService.deleteManual(id);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.NOTICE_AUTO_READONLY, result.getError());
        assertEquals(0, noticeMapper.get(id).getDeleted());
    }

    @Test
    @DisplayName("后台视图：自动公告标记为不可编辑，并带上来源")
    void listForAdmin_marksAutoNoticeNotEditable() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 42L, "系统写的消息");

        AdminNoticeVo vo = noticeService.listForAdmin(1, 10, null).getData().getRecords().get(0);

        assertEquals(NoticePublishMode.AUTO.name(), vo.getPublishMode());
        assertEquals("系统", vo.getPublishModeText());
        assertFalse(vo.isEditable());
        assertEquals(NoticeSourceType.DEVICE.name(), vo.getSourceType());
        assertEquals("机台", vo.getSourceTypeText());
        assertEquals(42L, vo.getSourceId(), "后台能看到来源 ID —— 运营要靠它定位是哪台机器");
    }

    // ==================================================================
    // 后台列表
    // ==================================================================

    @Test
    @DisplayName("后台列表：可按发布方式筛选")
    void listForAdmin_filtersByPublishMode() {
        noticeService.publishAuto(NoticeSourceType.DEVICE, 42L, "自动的");
        noticeService.createManual(99L, request("手写的"));

        assertEquals(2, countAdmin(null));
        assertEquals(1, countAdmin(NoticePublishMode.AUTO.name()));
        assertEquals(1, countAdmin(NoticePublishMode.MANUAL.name()));
    }

    @Test
    @DisplayName("后台列表：分页生效，倒序排列")
    void listForAdmin_paginates() {
        for (int i = 0; i < 12; i++) {
            noticeService.publishAuto(NoticeSourceType.DEVICE, 1L, "消息 " + i);
        }

        PageResult<AdminNoticeVo> first = noticeService.listForAdmin(1, 10, null).getData();

        assertEquals(12, first.getTotal());
        assertEquals(10, first.getRecords().size());
        assertEquals("消息 11", first.getRecords().get(0).getTitle(), "最新的在前");
        assertEquals(2, noticeService.listForAdmin(2, 10, null).getData().getRecords().size());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 统计后台列表条数。
     *
     * @param publishMode 发布方式筛选，可空
     * @return 总条数
     */
    private long countAdmin(String publishMode) {
        return noticeService.listForAdmin(1, 100, publishMode).getData().getTotal();
    }

    /**
     * 造一个发布手写公告的请求。
     *
     * @param title 标题
     * @return 请求对象
     */
    private static CreateNoticeRequest request(String title) {
        CreateNoticeRequest request = new CreateNoticeRequest();
        request.setTitle(title);
        return request;
    }

    /**
     * 造一条手写公告实体。
     *
     * @param title   标题
     * @param content 正文，可空
     * @return 公告实体
     */
    private static Notice manual(String title, String content) {
        Notice notice = new Notice();
        notice.setTitle(title);
        notice.setContent(content);
        notice.setPublishMode(NoticePublishMode.MANUAL.name());
        return notice;
    }

    /**
     * 造一条自动公告实体。
     *
     * @param title 标题
     * @return 公告实体
     */
    private static Notice auto(String title) {
        Notice notice = new Notice();
        notice.setTitle(title);
        notice.setPublishMode(NoticePublishMode.AUTO.name());
        notice.setSourceType(NoticeSourceType.DEVICE.name());
        notice.setSourceId(1L);
        return notice;
    }
}
