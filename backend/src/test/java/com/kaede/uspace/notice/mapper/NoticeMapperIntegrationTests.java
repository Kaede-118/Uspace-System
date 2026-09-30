package com.kaede.uspace.notice.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.notice.NoticePublishMode;
import com.kaede.uspace.notice.NoticeSourceType;
import com.kaede.uspace.notice.entity.Notice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NoticeMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过；
 * 正文里带 {@code 999} 前缀这类可辨认的标记，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么假 Mapper 不够、必须连真库</b>：假实现模拟的是数据库行为的语义，
 * 却绕过了真实 SQL。本模块有两处只有真库才验证得到：
 * <ul>
 *   <li><b>手写 SQL 里的 {@code deleted = 0}</b> —— 全局逻辑删除配置只作用于
 *       MyBatis-Plus 自己生成的方法，注解里手写的 SQL 漏了这一句不会有任何报错，
 *       只会让「已下架的手写公告还挂在首页上」</li>
 *   <li><b>CHECK 约束 {@code ck_notice_source}</b> —— 它是「手写无来源、自动必有来源」
 *       这条规则在数据库侧的第二道防线。约束写错、写漏、或建表时根本没带上，
 *       应用层完全感知不到，直到某天有人绕过 Service 直接写库</li>
 * </ul>
 *
 * <p><b>断言为什么都盯着自己插入的那几条</b>：{@code biz_notice} 没有门店一类的
 * 隔离维度（不像 {@code DeviceMapperIntegrationTests} 能拿一个不存在的门店 ID 圈出
 * 干净的数据），所以这里不去断言「总数是多少」—— 那会把测试绑死在「库是空的」这个
 * 前提上。改为验证<b>相对性质</b>：顺序、筛选、分页不重不漏。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class NoticeMapperIntegrationTests {

    /** 测试用的机台 ID，取大数以免与真实机台混淆 */
    private static final Long DEVICE_ID = 999901L;

    /** 测试用的管理员 ID，同理 */
    private static final Long ADMIN_ID = 999901L;

    @Autowired
    private NoticeMapper noticeMapper;

    // ==================================================================
    // 落库与映射
    // ==================================================================

    @Test
    @DisplayName("插入时自动填充审计字段并回填主键")
    void insert_fillsAuditFieldsAndId() {
        Notice notice = newManualNotice("测试公告", "正文");

        int affected = noticeMapper.insert(notice);

        assertEquals(1, affected, "插入一行");
        assertNotNull(notice.getId(), "主键应当由数据库自增填回实体");
        assertNotNull(notice.getCreatedAt(), "created_at 是 NOT NULL 且无默认值，靠自动填充补上");
        assertNotNull(notice.getUpdatedAt(), "updated_at 同理");
    }

    @Test
    @DisplayName("手写公告的各列都能正确落库与读出")
    void insert_mapsAllColumns() {
        Notice notice = newManualNotice("明天停业半天", "上午做设备保养，下午照常营业");
        noticeMapper.insert(notice);

        Notice loaded = noticeMapper.selectById(notice.getId());

        assertEquals("明天停业半天", loaded.getTitle());
        assertEquals("上午做设备保养，下午照常营业", loaded.getContent());
        assertEquals(NoticePublishMode.MANUAL.name(), loaded.getPublishMode(),
                "发布方式是 VARCHAR，映射写错不会报错，只有查回来才知道");
        assertNull(loaded.getSourceType(), "手写公告不挂来源");
        assertNull(loaded.getSourceId());
        assertEquals(ADMIN_ID, loaded.getCreatedBy(), "发布人要落库，后台列表靠它显示是谁发的");
    }

    @Test
    @DisplayName("自动公告没有正文与发布人")
    void insert_autoNoticeHasNoContentNorAuthor() {
        Notice notice = newAutoNotice("拍拍机 1 号 转为维护中");
        noticeMapper.insert(notice);

        Notice loaded = noticeMapper.selectById(notice.getId());

        assertNull(loaded.getContent(), "一条事件通知一句话就够了，自动公告不该带正文");
        assertNull(loaded.getCreatedBy(), "自动公告不是「谁」发的，是系统按规则记下的");
        assertEquals(NoticeSourceType.DEVICE.name(), loaded.getSourceType());
        assertEquals(DEVICE_ID, loaded.getSourceId());
    }

    // ==================================================================
    // 用户端：首页公告栏
    // ==================================================================

    @Test
    @DisplayName("首页取最新几条：按 id 倒序，最新的在最上面")
    void selectLatest_ordersByIdDesc() {
        Notice older = newManualNotice("先发的", null);
        Notice newer = newManualNotice("后发的", null);
        noticeMapper.insert(older);
        noticeMapper.insert(newer);

        List<Notice> latest = noticeMapper.selectLatest(2);

        assertEquals(2, latest.size(), "自增主键只增不减，新插入的两条 id 必然最大");
        assertEquals(newer.getId(), latest.get(0).getId(), "最新的在最上面");
        assertEquals(older.getId(), latest.get(1).getId());
    }

    @Test
    @DisplayName("首页条数受 LIMIT 约束")
    void selectLatest_respectsLimit() {
        for (int i = 0; i < 3; i++) {
            noticeMapper.insert(newManualNotice("第 " + i + " 条", null));
        }

        assertEquals(1, noticeMapper.selectLatest(1).size(),
                "LIMIT 没生效的话会整表拉回来，首页公告栏会变成一条长列表");
    }

    @Test
    @DisplayName("下架的手写公告不再出现在首页")
    void selectLatest_excludesDeleted() {
        Notice kept = newManualNotice("还在的", null);
        Notice removed = newManualNotice("已下架的", null);
        noticeMapper.insert(kept);
        noticeMapper.insert(removed);

        noticeMapper.deleteById(removed.getId());

        List<Long> ids = noticeMapper.selectLatest(10).stream().map(Notice::getId).toList();
        assertTrue(ids.contains(kept.getId()));
        assertTrue(!ids.contains(removed.getId()),
                "手写 SQL 里的 deleted = 0 漏了的话，删掉的公告还挂在首页上，界面上看不出来");
    }

    // ==================================================================
    // 后台：分页与筛选
    // ==================================================================

    @Test
    @DisplayName("后台分页逐页取完：不重复、不漏，每页不超量")
    void selectPageForAdmin_pagesWithoutOverlapOrGap() {
        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Notice manual = newManualNotice("手写 " + i, null);
            noticeMapper.insert(manual);
            inserted.add(manual.getId());
        }
        for (int i = 0; i < 2; i++) {
            Notice auto = newAutoNotice("自动 " + i);
            noticeMapper.insert(auto);
            inserted.add(auto.getId());
        }

        // 逐页翻完，收集所有出现过的 id
        Set<Long> seen = new HashSet<>();
        int pageNum = 1;
        boolean hasNext = true;
        while (hasNext) {
            Page<Notice> page = new Page<>(pageNum, 2);
            noticeMapper.selectPageForAdmin(page, null);

            assertTrue(page.getRecords().size() <= 2, "分页插件没生效的话每页会是全表");

            for (Notice notice : page.getRecords()) {
                assertTrue(seen.add(notice.getId()),
                        "同一条公告出现在了两页里 —— 翻页时会看到重复内容");
            }
            hasNext = page.hasNext();
            pageNum++;
        }

        assertTrue(seen.containsAll(inserted),
                "翻完所有页却没见到自己插入的公告 —— 分页丢数据比重复更隐蔽");
    }

    @Test
    @DisplayName("后台按发布方式筛选")
    void selectPageForAdmin_filtersByPublishMode() {
        Notice manual = newManualNotice("手写公告", null);
        Notice auto = newAutoNotice("自动公告");
        noticeMapper.insert(manual);
        noticeMapper.insert(auto);

        List<Long> autoIds = collectAllIds(NoticePublishMode.AUTO.name());
        List<Long> manualIds = collectAllIds(NoticePublishMode.MANUAL.name());

        assertTrue(autoIds.contains(auto.getId()));
        assertTrue(!autoIds.contains(manual.getId()),
                "想改文案时得先知道「哪些是能改的」，筛错了会把自动公告列进去");
        assertTrue(manualIds.contains(manual.getId()));
        assertTrue(!manualIds.contains(auto.getId()));
    }

    @Test
    @DisplayName("筛选参数为 null 或空串时不过滤")
    void selectPageForAdmin_blankModeReturnsAll() {
        Notice manual = newManualNotice("手写公告", null);
        Notice auto = newAutoNotice("自动公告");
        noticeMapper.insert(manual);
        noticeMapper.insert(auto);

        assertTrue(collectAllIds(null).containsAll(List.of(manual.getId(), auto.getId())),
                "不传筛选时后台要看到全部，否则运营会以为公告丢了");
        assertTrue(collectAllIds("").containsAll(List.of(manual.getId(), auto.getId())),
                "前端把没选的筛选项传成空串是常见写法，不能因此把结果筛空");
    }

    // ==================================================================
    // 后台：修改手写公告
    // ==================================================================

    @Test
    @DisplayName("改动手写公告时能把正文清空")
    void updateManualFields_clearsContent() {
        Notice notice = newManualNotice("原标题", "原正文");
        noticeMapper.insert(notice);

        int affected = noticeMapper.updateManualFields(notice.getId(), "新标题", null);

        assertEquals(1, affected);
        Notice loaded = noticeMapper.selectById(notice.getId());
        assertEquals("新标题", loaded.getTitle());
        assertNull(loaded.getContent(),
                "这条语句必须手写：updateById 会跳过 null 字段，正文根本清不掉，而且不报错");
        assertEquals(NoticePublishMode.MANUAL.name(), loaded.getPublishMode(), "编辑不该改变公告的身份");
    }

    @Test
    @DisplayName("自动公告改不动：受影响行数为 0 且内容原样")
    void updateManualFields_leavesAutoNoticeUntouched() {
        Notice auto = newAutoNotice("拍拍机 1 号 转为维护中");
        noticeMapper.insert(auto);

        int affected = noticeMapper.updateManualFields(auto.getId(), "被人改过的标题", "被人补的正文");

        assertEquals(0, affected,
                "WHERE 里的 publish_mode = 'MANUAL' 是数据库侧的第二道保险，Service 先查过也不能省它");
        Notice loaded = noticeMapper.selectById(auto.getId());
        assertEquals("拍拍机 1 号 转为维护中", loaded.getTitle(),
                "受影响行数为 0 却不是真的 0 —— 自动公告被改写等于篡改历史，且界面上看不出来");
        assertNull(loaded.getContent());
    }

    @Test
    @DisplayName("已下架的公告改不动")
    void updateManualFields_ignoresDeletedNotice() {
        Notice notice = newManualNotice("原标题", null);
        noticeMapper.insert(notice);
        noticeMapper.deleteById(notice.getId());

        int affected = noticeMapper.updateManualFields(notice.getId(), "改已删的", null);

        assertEquals(0, affected, "改一条已经下架的公告没有意义，改到了反而说明 deleted 条件漏了");
    }

    // ==================================================================
    // 数据库侧的第二道防线：CHECK 约束
    // ==================================================================

    @Test
    @DisplayName("自动公告缺来源时被数据库拒绝")
    void checkConstraint_rejectsAutoWithoutSource() {
        Notice notice = new Notice();
        notice.setTitle("来源缺失的自动公告");
        notice.setPublishMode(NoticePublishMode.AUTO.name());
        // 刻意不设 sourceType / sourceId

        RuntimeException e = assertThrows(RuntimeException.class, () -> noticeMapper.insert(notice));

        assertTrue(String.valueOf(e.getMessage()).contains("ck_notice_source"),
                "该被 CHECK 约束挡下，实际报的是：" + e.getMessage());
    }

    @Test
    @DisplayName("手写公告挂着来源时被数据库拒绝")
    void checkConstraint_rejectsManualWithSource() {
        Notice notice = newManualNotice("挂了来源的手写公告", null);
        notice.setSourceType(NoticeSourceType.DEVICE.name());
        notice.setSourceId(DEVICE_ID);

        RuntimeException e = assertThrows(RuntimeException.class, () -> noticeMapper.insert(notice));

        assertTrue(String.valueOf(e.getMessage()).contains("ck_notice_source"),
                "该被 CHECK 约束挡下，实际报的是：" + e.getMessage());
    }

    // ==================================================================
    // 测试数据构造
    // ==================================================================

    /**
     * 构造一条手写公告。
     *
     * @param title   标题
     * @param content 正文，可为 null
     * @return 公告实体（未落库）
     */
    private static Notice newManualNotice(String title, String content) {
        Notice notice = new Notice();
        notice.setTitle(title);
        notice.setContent(content);
        notice.setPublishMode(NoticePublishMode.MANUAL.name());
        notice.setSourceType(null);
        notice.setSourceId(null);
        notice.setCreatedBy(ADMIN_ID);
        return notice;
    }

    /**
     * 构造一条机台自动公告。
     *
     * @param title 标题
     * @return 公告实体（未落库）
     */
    private static Notice newAutoNotice(String title) {
        Notice notice = new Notice();
        notice.setTitle(title);
        notice.setContent(null);
        notice.setPublishMode(NoticePublishMode.AUTO.name());
        notice.setSourceType(NoticeSourceType.DEVICE.name());
        notice.setSourceId(DEVICE_ID);
        notice.setCreatedBy(null);
        return notice;
    }

    /**
     * 按发布方式翻完所有页，收集全部公告 ID。
     *
     * @param publishMode 发布方式筛选，可为 null
     * @return 所有符合条件的公告 ID
     */
    private List<Long> collectAllIds(String publishMode) {
        List<Long> ids = new ArrayList<>();
        int pageNum = 1;
        boolean hasNext = true;
        while (hasNext) {
            Page<Notice> page = new Page<>(pageNum, 50);
            noticeMapper.selectPageForAdmin(page, publishMode);
            page.getRecords().forEach(n -> ids.add(n.getId()));
            hasNext = page.hasNext();
            pageNum++;
        }
        return ids;
    }
}
