package com.kaede.uspace.qqbot;

import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.BillingRulesVo;
import com.kaede.uspace.billing.event.FreePeriodChangeAction;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.device.dto.DeviceDisplayVo;
import com.kaede.uspace.device.dto.DeviceGroupVo;
import com.kaede.uspace.notice.NoticePublishMode;
import com.kaede.uspace.notice.dto.NoticeVo;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.order.dto.MonthSpentVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.promotion.dto.CardTypeVo;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.event.ClosureChangeAction;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QqReplyText} 的单元测试。
 *
 * <p><b>纯单测，连 Spring 都不需要。</b>
 *
 * <p>这一层的风险与 {@link QqCommandParser} 同类：<b>写错了不会报错</b>，
 * 只会答非所问或把数字说错。而群消息一旦发出去就收不回来 ——
 * 一条「封顶 4E+1 元」会一直躺在群里，直到有人翻聊天记录时才发现。
 */
class QqReplyTextTests {

    // ==================================================================
    // 凭证被驳回的提醒
    // ==================================================================

    @Test
    @DisplayName("凭证驳回提醒：带上原因与入口，且开头留一个空格给 @ 段")
    void proofRejected_carriesReasonAndLink() {
        String text = QqReplyText.proofRejected("截图看不清金额", "http://host/#/orders");

        assertTrue(text.contains("截图看不清金额"),
                "原因是管理员写给顾客看的那句话，必须原样出现 —— 少了它用户不知道该改什么");
        assertTrue(text.contains("http://host/#/orders"), "要给一个点得进去的入口");
        assertTrue(text.startsWith(" "),
                "开头那个空格是给 @ 段留的：不留的话群里会显示成"
                        + "「@张三你的付款凭证……」两截挤在一起");
    }

    @Test
    @DisplayName("凭证驳回提醒：引导用户重新上传")
    void proofRejected_guidesResubmit() {
        String text = QqReplyText.proofRejected("金额对不上", "http://host/#/orders");

        assertTrue(text.contains("重新上传"),
                "驳回会把订单退回待支付，用户可以自助重交 —— 提醒里就要说清楚该做什么。"
                        + "（这条用例 2026-10-04 翻过一次：在此之前系统对「已支付 + 已驳回」"
                        + "明确拒绝重交，文案只能写「点这里看看」，因为喊了也做不到）");
    }

    // ==================================================================
    // 时长格式化
    // ==================================================================

    @Test
    @DisplayName("时长：整点与零头分别怎么说")
    void duration_formatsHoursAndMinutes() {
        assertEquals("0 分钟", QqReplyText.duration(0));
        assertEquals("59 分钟", QqReplyText.duration(59));
        assertEquals("1 小时", QqReplyText.duration(60), "整点不带「0 分」");
        assertEquals("1 小时 1 分", QqReplyText.duration(61));
        assertEquals("2 小时 40 分", QqReplyText.duration(160));
    }

    // ==================================================================
    // 在店名册
    // ==================================================================

    @Test
    @DisplayName("名册：没有人时说一句人话，而不是空表")
    void instore_saysSoWhenEmpty() {
        // 只断言「有回话」，不锁具体句子 —— 措辞改了不该让这条红，
        // 但回一句空消息（群里等于哑巴）必须红
        assertFalse(QqReplyText.instore(List.of(), Map.of(), 30).isBlank(),
                "没人时也要回一句话，不能返回空串");
    }

    @Test
    @DisplayName("名册：列出人数、时长、月卡与偏好")
    void instore_listsPeople() {
        InstoreUserVo user = new InstoreUserVo();
        user.setUserId(1L);
        user.setNickname("张三");
        user.setStayMinutes(80);
        user.setCardTypeLabel("全天月卡");
        user.setPreference("PAIPAI");

        String text = QqReplyText.instore(List.of(user), Map.of("PAIPAI", "拍拍机"), 30);

        assertTrue(text.contains("当前 1 人在店"), text);
        assertTrue(text.contains("1. [全天月卡] 张三 · 1 小时 20 分 · 偏好 拍拍机"), text);
    }

    @Test
    @DisplayName("名册：⚠️ STAFF 与月卡挂在名字【前面】—— 扫一眼行首就分得清")
    void instore_putsBadgesBeforeName() {
        InstoreUserVo staff = new InstoreUserVo();
        staff.setUserId(1L);
        staff.setNickname("Kaede");
        staff.setRole("ADMIN");
        staff.setCardTypeLabel("夜间月卡");
        staff.setStayMinutes(10);

        InstoreUserVo guest = new InstoreUserVo();
        guest.setUserId(2L);
        guest.setNickname("顾客甲");
        guest.setRole("USER");
        guest.setStayMinutes(5);

        String text = QqReplyText.instore(List.of(staff, guest), Map.of(), 30);

        assertTrue(text.contains("1. [STAFF][夜间月卡] Kaede · 10 分钟"), text);
        assertTrue(text.contains("2. 顾客甲 · 5 分钟"),
                "普通顾客没有标记，也不该凭空多出方括号：" + text);
    }

    @Test
    @DisplayName("名册：角色缺失（用户记录查不到）时不挂 STAFF")
    void instore_withoutRoleIsNotStaff() {
        InstoreUserVo user = new InstoreUserVo();
        user.setUserId(9L);
        user.setNickname("无名");
        user.setRole(null);
        user.setStayMinutes(3);

        String text = QqReplyText.instore(List.of(user), Map.of(), 30);

        assertFalse(text.contains("STAFF"),
                "⚠️ role 为 null 时判成店员的话，半个群都会被挂上 STAFF 徽章：" + text);
        assertTrue(text.contains("1. 无名 · 3 分钟"), text);
    }

    @Test
    @DisplayName("名册：⚠️ 映射不出的偏好 code 直接丢弃，不把内部代号甩到群里")
    void instore_dropsUnknownPreferenceCode() {
        InstoreUserVo user = new InstoreUserVo();
        user.setUserId(2L);
        user.setNickname("李四");
        user.setPreference("PAIPAI,RIMA");

        String text = QqReplyText.instore(List.of(user), Map.of("PAIPAI", "拍拍机"), 30);

        assertFalse(text.contains("RIMA"), "字典里查不到的 code 不该原样出现：" + text);
        assertTrue(text.contains("偏好 拍拍机"), text);
    }

    @Test
    @DisplayName("名册：昵称为空时用「用户{ID}」兜底，不能把这一行抹掉")
    void instore_fallsBackToUserId() {
        InstoreUserVo user = new InstoreUserVo();
        user.setUserId(7L);
        user.setNickname(null);
        user.setStayMinutes(0);

        String text = QqReplyText.instore(List.of(user), Map.of(), 30);

        assertTrue(text.contains("用户7"), "名册上有几个人就得说几个人：" + text);
        assertTrue(text.contains("刚进店"), "0 分钟在名册里说「刚进店」更自然");
    }

    @Test
    @DisplayName("名册：超过上限时截断并说明还有多少人")
    void instore_truncatesBeyondLimit() {
        List<InstoreUserVo> users = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            InstoreUserVo user = new InstoreUserVo();
            user.setUserId((long) i);
            user.setNickname("用户" + i);
            users.add(user);
        }

        String text = QqReplyText.instore(users, Map.of(), 3);

        assertTrue(text.contains("当前 5 人在店"), text);
        assertTrue(text.contains("……还有 2 人"), "截断必须说明，否则看起来像少了人：" + text);
    }

    // ==================================================================
    // 包场时间表
    // ==================================================================

    @Test
    @DisplayName("包场：没有安排时说一句人话")
    void bookingSchedule_saysSoWhenEmpty() {
        assertFalse(QqReplyText.bookingSchedule(
                        List.of(), Duration.ofMinutes(15), LocalDate.of(2026, 10, 4)).isBlank(),
                "没有包场时也要回一句话，不能返回空串");
    }

    @Test
    @DisplayName("包场：今天/明天用相对说法，进行中的标出来，提前量取自入参")
    void bookingSchedule_labelsDaysAndLead() {
        LocalDate today = LocalDate.of(2026, 10, 4);
        BookingScheduleVo todayItem = new BookingScheduleVo();
        todayItem.setStartAt(LocalDateTime.of(2026, 10, 4, 14, 0));
        todayItem.setEndAt(LocalDateTime.of(2026, 10, 4, 16, 0));
        todayItem.setOngoing(true);
        BookingScheduleVo tomorrowItem = new BookingScheduleVo();
        tomorrowItem.setStartAt(LocalDateTime.of(2026, 10, 5, 19, 0));
        tomorrowItem.setEndAt(LocalDateTime.of(2026, 10, 5, 21, 0));

        String text = QqReplyText.bookingSchedule(
                List.of(todayItem, tomorrowItem), Duration.ofMinutes(15), today);

        assertTrue(text.contains("今天 14:00 – 16:00（进行中）"), text);
        assertTrue(text.contains("明天 19:00 – 21:00"), text);
        assertTrue(text.contains("开始前 15 分钟停止接待"), "提前量取自配置，不写死：" + text);
    }

    // ==================================================================
    // 包场与公告的群播报（新增包场 / 发布公告时主动推的那两条）
    // ==================================================================

    @Test
    @DisplayName("包场播报：日期用相对说法，时段写法与 fw包场 指令逐字一致")
    void bookingActivated_usesScheduleFormat() {
        String text = QqReplyText.bookingActivated(
                LocalDateTime.of(2026, 10, 5, 14, 0),
                LocalDateTime.of(2026, 10, 5, 18, 0),
                LocalDate.of(2026, 10, 4));

        assertTrue(text.contains("明天 14:00 – 18:00"),
                "日期用相对说法、时段写法要与 fw包场 指令一致 —— "
                        + "两处不一致的话，同一场包场在群里会有两种说法：" + text);
    }

    @Test
    @DisplayName("包场播报：不带金额、不带包场人")
    void bookingActivated_noAmountNoHost() {
        String text = QqReplyText.bookingActivated(
                LocalDateTime.of(2026, 10, 4, 14, 0),
                LocalDateTime.of(2026, 10, 4, 18, 0),
                LocalDate.of(2026, 10, 4));

        assertTrue(text.contains("今天 14:00 – 18:00"), text);
        assertFalse(text.contains("¥"),
                "群消息所有人可见，这场收了多少钱属经营信息，只进店主群：" + text);
    }

    @Test
    @DisplayName("商品购买播报：昵称、商品名、数量与剩余量都在")
    void productPurchased_carriesQuantityAndStock() {
        String text = QqReplyText.productPurchased("Kaede", "魔爪", 2, 3);

        assertTrue(text.contains("Kaede"), text);
        assertTrue(text.contains("魔爪"), text);
        assertTrue(text.contains("×2"), "只报商品名的话群里看不出买了几件：" + text);
        assertTrue(text.contains("当前剩余 3"), "「还剩多少」报的是可售量，与商城页、fw菜单 同一口径：" + text);
        assertFalse(text.contains("¥"), "这条对所有群播同一份文本，金额不进群：" + text);
    }

    @Test
    @DisplayName("商品购买播报：查不到剩余量时降级为不带「还剩」的一版")
    void productPurchased_withoutStockDegrades() {
        String text = QqReplyText.productPurchased("Kaede", "魔爪", 1, null);

        assertTrue(text.contains("魔爪"), text);
        assertFalse(text.contains("还剩"),
                "查不到就如实不说 —— 编一个数出来，群里没人分得清真假：" + text);
    }

    @Test
    @DisplayName("商品购买播报：数量为 null 时按 1 件说，不能印出 ×null")
    void productPurchased_nullQuantityFallsBackToOne() {
        String text = QqReplyText.productPurchased("Kaede", "魔爪", null, 3);

        assertTrue(text.contains("×1"), text);
        assertFalse(text.contains("null"), "「×null」会直接发到群里，而没有任何地方会报错：" + text);
    }

    @Test
    @DisplayName("停业播报：新增带原因与时段，日期写法与包场播报一致")
    void closureChanged_createdCarriesReason() {
        String text = QqReplyText.closureChanged(ClosureChangeAction.CREATED,
                LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 14, 0),
                "设备维护", LocalDate.of(2026, 10, 4));

        assertTrue(text.contains("明天 10:00 – 14:00"), "时段要带上：" + text);
        assertTrue(text.contains("设备维护"), "原因要带上，让群里知道为什么不开：" + text);
    }

    @Test
    @DisplayName("停业播报：撤销带原时段、不重复原因 —— 撤的是哪一段要认得出")
    void closureChanged_deletedCarriesRange() {
        String text = QqReplyText.closureChanged(ClosureChangeAction.DELETED,
                LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 14, 0),
                "设备维护", LocalDate.of(2026, 10, 4));

        assertTrue(text.contains("明天 10:00 – 14:00"),
                "撤销也要带上原时段 —— 群里可能积着好几条停业安排，"
                        + "只说「已撤销」没人知道撤的是哪一段：" + text);
    }

    @Test
    @DisplayName("免费活动播报：没填名称时不印出空括号")
    void freePeriodChanged_createdWithoutReason() {
        String text = QqReplyText.freePeriodChanged(FreePeriodChangeAction.CREATED,
                LocalDateTime.of(2026, 10, 5, 20, 0),
                LocalDateTime.of(2026, 10, 5, 22, 0),
                null, LocalDate.of(2026, 10, 4));

        assertTrue(text.contains("明天 20:00 – 22:00"), text);
        assertFalse(text.contains("（）"),
                "名称是选填的，没填时不能印出空括号 —— 那像是系统出了错：" + text);
    }

    @Test
    @DisplayName("免费活动播报：撤销明说「恢复按时长计费」")
    void freePeriodChanged_deleted() {
        String text = QqReplyText.freePeriodChanged(FreePeriodChangeAction.DELETED,
                LocalDateTime.of(2026, 10, 5, 20, 0),
                LocalDateTime.of(2026, 10, 5, 22, 0),
                "周年庆", LocalDate.of(2026, 10, 4));

        assertTrue(text.contains("明天 20:00 – 22:00"), text);
        assertTrue(text.contains("计费"),
                "撤销要明说恢复计费，免得有人以为还能白玩：" + text);
    }

    @Test
    @DisplayName("包场撤销播报：时段写法与「已安排包场」逐字同一套")
    void bookingRevoked_matchesScheduleFormat() {
        String text = QqReplyText.bookingRevoked(
                LocalDateTime.of(2026, 10, 5, 14, 0),
                LocalDateTime.of(2026, 10, 5, 18, 0),
                LocalDate.of(2026, 10, 4));

        assertTrue(text.contains("明天 14:00 – 18:00"),
                "时段写法要与几天前那条生效消息对得上，否则读不出这两条说的是同一场包场：" + text);
    }

    @Test
    @DisplayName("公告播报：手写公告带正文与详情链接")
    void noticePublished_manualCarriesContentAndLink() {
        String text = QqReplyText.noticePublished(NoticePublishMode.MANUAL,
                "本周六场地维护", "10:00–14:00 暂停营业", "http://host/#/notices");

        assertTrue(text.contains("本周六场地维护"), "标题要在：" + text);
        assertTrue(text.contains("10:00–14:00 暂停营业"), "手写公告的正文才是重点：" + text);
        assertTrue(text.contains("http://host/#/notices"), "要给详情链接：" + text);
    }

    @Test
    @DisplayName("公告播报：手写公告没填正文时只发标题与链接")
    void noticePublished_manualWithoutContent() {
        String text = QqReplyText.noticePublished(NoticePublishMode.MANUAL,
                "仅标题", null, "http://host/#/notices");

        assertTrue(text.contains("仅标题"), text);
        assertTrue(text.contains("http://host/#/notices"), text);
        assertFalse(text.contains("\n\n"),
                "正文是选填的，没填时不该留一个空行：" + text);
    }

    @Test
    @DisplayName("公告播报：自动公告只发标题 —— 标题本身已是完整的事件描述")
    void noticePublished_autoIsTitleOnly() {
        String text = QqReplyText.noticePublished(NoticePublishMode.AUTO,
                "拍拍机 1 号 由 良好 转为 维护中", null, "http://host/#/notices");

        assertTrue(text.contains("拍拍机 1 号 由 良好 转为 维护中"), text);
        assertFalse(text.contains("详情"),
                "自动公告没有正文，点进去也只是列表页，链接是多余的：" + text);
    }

    @Test
    @DisplayName("公告播报：长正文压成一行并截断到 60 字")
    void noticePublished_flattensAndTruncates() {
        String content = "一".repeat(30) + "\n" + "二".repeat(40);

        String text = QqReplyText.noticePublished(NoticePublishMode.MANUAL,
                "标题", content, "http://host/#/notices");

        String expectedBody = "一".repeat(30) + " " + "二".repeat(29) + "…";
        assertTrue(text.contains(expectedBody),
                "换行压成空格、按字符截到 60 字再加省略号 —— 按字节截会切出半个汉字"
                        + "（群里显示成乱码方块），不压平则一条播报铺满整屏：" + text);
        assertFalse(text.contains("\n\n"), "不该在群里铺成多行：" + text);
    }

    // ==================================================================
    // 价格
    // ==================================================================

    @Test
    @DisplayName("价格：日场夜场单价与封顶，数字不出现科学计数法")
    void price_listsRates() {
        String text = QqReplyText.price(BillingRulesVo.from(new BillingProperties()));

        assertFalse(text.contains("E+"), "⚠️ stripTrailingZeros 之后必须 toPlainString：" + text);
        assertTrue(text.contains("元/小时"), text);
        assertTrue(text.contains("封顶"), text);
        assertTrue(text.contains("分钟免费"), text);
    }

    // ==================================================================
    // 商城菜单
    // ==================================================================

    @Test
    @DisplayName("菜单：报可售量（不是实际库存），卖完显示余 0")
    void menu_listsAvailableStockAndSoldOut() {
        ProductVo water = new ProductVo();
        water.setName("矿泉水");
        water.setPrice(new BigDecimal("2.00"));
        water.setStock(20);
        water.setAvailableStock(18);   // 有两件被未支付的单子占着
        water.setSoldOut(false);

        ProductVo chips = new ProductVo();
        chips.setName("薯片");
        chips.setPrice(new BigDecimal("5.00"));
        chips.setStock(0);
        chips.setAvailableStock(0);
        chips.setSoldOut(true);

        String text = QqReplyText.menu(List.of(water, chips), "http://x/#/mall", true);

        assertTrue(text.contains("矿泉水 --- ¥2.00 -- 余（18）"),
                "报的是可售量（18）而不是实际库存（20）—— 与下单校验同一口径，"
                        + "否则会出现「群里说还剩 20 件、下单却说卖完了」：" + text);
        assertTrue(text.contains("薯片 --- ¥5.00 -- 余（0）"),
                "卖完就是余（0），不另起一个「售罄」的说法：" + text);
        assertTrue(text.contains("共 2 种"), text);
        assertTrue(text.contains("http://x/#/mall"), "要给下单入口：" + text);
    }

    @Test
    @DisplayName("菜单：没有上架商品时回一句人话")
    void menu_saysSoWhenEmpty() {
        assertFalse(QqReplyText.menu(List.of(), "http://x/#/mall", true).isBlank(),
                "没有商品时也要回一句话，不能返回空串");
    }

    // ==================================================================
    // 店内设施
    // ==================================================================

    @Test
    @DisplayName("设施：按类型分组，报台数与状况，维护中的照列")
    void deviceList_listsGroupedDevices() {
        DeviceGroupVo paipai = deviceGroup("PAIPAI", "拍拍机", List.of(
                deviceDisplay("拍拍机 1 号", "良好", "靠窗第二台"),
                deviceDisplay("拍拍机 2 号", "维护中", null)));
        DeviceGroupVo taisheng = deviceGroup("TAISHENG", "抬手乐", List.of(
                deviceDisplay("抬手乐 1 号", "待维护", null)));

        String text = QqReplyText.deviceList(List.of(paipai, taisheng), "http://x/#/devices");

        assertTrue(text.contains("共 3 台"), "台数是跨全部类型数的：" + text);
        assertTrue(text.contains("【拍拍机】") && text.contains("【抬手乐】"),
                "按类型分组，组标题用后端给的类型名：" + text);
        assertTrue(text.contains("拍拍机 1 号 --- 良好 -- 靠窗第二台"),
                "状况是主信息、位置有才显示：" + text);
        assertTrue(text.contains("拍拍机 2 号 --- 维护中"),
                "⚠️ 维护中的照列 —— 藏起来会让顾客以为机器搬走了：" + text);
        assertTrue(text.contains("抬手乐 1 号 --- 待维护"),
                "状况用后端给的中文，不在这里另翻一份：" + text);
        assertTrue(text.contains("http://x/#/devices"), "要给网页端入口：" + text);
    }

    @Test
    @DisplayName("设施：一台都没有时回一句人话（含空组与 null）")
    void deviceList_saysSoWhenEmpty() {
        assertFalse(QqReplyText.deviceList(List.of(), "http://x/#/devices").isBlank(),
                "没有机台时也要回一句话，不能返回空串");
        assertFalse(QqReplyText.deviceList(null, "http://x/#/devices").isBlank(),
                "null 同样给人话 —— 不能抛异常");
        assertTrue(QqReplyText.deviceList(
                        List.of(deviceGroup("PAIPAI", "拍拍机", List.of())), "http://x/#/devices")
                        .contains("暂无机台"),
                "空组不该渲染出一个光秃秃的【拍拍机】，也不能说「共 0 台」");
    }

    // ==================================================================
    // 门店公告
    // ==================================================================

    @Test
    @DisplayName("公告：标题带时间，手写公告带正文，超出条数时交代总数")
    void notices_listsTitleTimeAndBody() {
        NoticeVo manual = notice("本周六场地维护", "18:00–22:00 暂停接待，请提前安排。",
                LocalDateTime.of(2026, 10, 8, 16, 0));
        NoticeVo auto = notice("3 号机台由 良好 转为 维护中", null,
                LocalDateTime.of(2026, 10, 7, 9, 30));

        String text = QqReplyText.notices(List.of(manual, auto), 12, "http://x/#/notices");

        assertTrue(text.contains("共 12 条，显示最近 2 条"),
                "总数要交代 —— 只说五条的话，读者不知道后面还有没有：" + text);
        assertTrue(text.contains("📢 本周六场地维护 -- 10月8日 16:00"),
                "标题带时间：群里不会实时刷新，绝对时刻比「3 分钟前」稳：" + text);
        assertTrue(text.contains("18:00–22:00 暂停接待，请提前安排。"),
                "手写公告带一行正文：" + text);
        assertTrue(text.contains("📢 3 号机台由 良好 转为 维护中 -- 10月7日 09:30"),
                "自动公告没有正文，标题本身就是一句完整的事件描述：" + text);
        assertTrue(text.contains("http://x/#/notices"), "要给网页端入口：" + text);
    }

    @Test
    @DisplayName("公告：全部都在上面时不提「只显示最近几条」")
    void notices_hidesHintWhenAllShown() {
        String text = QqReplyText.notices(
                List.of(notice("暂停营业", null, LocalDateTime.of(2026, 10, 8, 16, 0))),
                1, "http://x/#/notices");

        assertTrue(text.contains("共 1 条"), text);
        assertFalse(text.contains("显示最近"), "全都在上面了，不必说「只显示」：" + text);
    }

    @Test
    @DisplayName("公告：长正文压平并截断（与发布播报共用一套 truncate）")
    void notices_truncatesLongBody() {
        String longBody = "第一行\n第二行".repeat(30);

        String text = QqReplyText.notices(
                List.of(notice("长公告", longBody, LocalDateTime.of(2026, 10, 8, 16, 0))),
                1, "http://x/#/notices");

        assertTrue(text.contains("…"), "超长要截断并给省略号：" + text);
        assertFalse(text.contains("第二行\n"), "换行要压平 —— 多行正文会把群消息撑得很难读：" + text);
    }

    @Test
    @DisplayName("公告：一条都没有时回一句人话（含 null）")
    void notices_saysSoWhenEmpty() {
        assertFalse(QqReplyText.notices(List.of(), 0, "http://x/#/notices").isBlank(),
                "没有公告时也要回一句话，不能返回空串");
        assertFalse(QqReplyText.notices(null, 0, "http://x/#/notices").isBlank(),
                "null 同样给人话 —— 不能抛异常");
    }

    // ==================================================================
    // 月卡说明
    // ==================================================================

    @Test
    @DisplayName("月卡：报卡种、价格与覆盖时段，价格保留两位")
    void cardTypes_listsTypesAndPrices() {
        CardTypeVo allDay = new CardTypeVo();
        allDay.setLabel("全天月卡");
        allDay.setPrice(new BigDecimal("600.00"));
        allDay.setPeriodText("不限时段");
        allDay.setValidDays(30);

        CardTypeVo night = new CardTypeVo();
        night.setLabel("夜间月卡");
        night.setPrice(new BigDecimal("320.00"));
        night.setPeriodText("22:00 – 次日 10:00");
        night.setValidDays(30);

        String text = QqReplyText.cardTypes(List.of(allDay, night), "http://x/#/cards");

        assertTrue(text.contains("月卡（有效期 30 天）"), "有效期从数据里来，不写死：" + text);
        assertTrue(text.contains("全天月卡 --- ¥600.00 -- 不限时段"), text);
        assertTrue(text.contains("夜间月卡 --- ¥320.00 -- 22:00 – 次日 10:00"), text);
        assertTrue(text.contains("http://x/#/cards"), "要给出买卡入口：" + text);
    }

    @Test
    @DisplayName("月卡：没有在售卡种时回一句人话")
    void cardTypes_saysSoWhenEmpty() {
        assertFalse(QqReplyText.cardTypes(List.of(), "http://x/#/cards").isBlank(),
                "没有在售卡种时也要回一句话，不能返回空串");
    }

    // ==================================================================
    // 帮助
    // ==================================================================

    @Test
    @DisplayName("帮助：写明前缀要求，写指令关掉时不列出那两条")
    void help_mentionsPrefixAndHidesDisabledWrites() {
        String withWrites = QqReplyText.help(true, false, null);
        assertTrue(withWrites.contains("前缀 fw"),
                "必须写清前缀要求，否则用户裸发指令什么都得不到：" + withWrites);
        assertFalse(withWrites.contains("/ 或 fw"),
                "⚠️ `/` 自 2026-10-09 起已不是前缀（发了会被当闲聊静默），"
                        + "帮助里再写它等于教人发一条没有反应的指令：" + withWrites);
        assertTrue(withWrites.contains("fw开门"), withWrites);
        assertTrue(withWrites.contains("fw结账"), withWrites);
        assertTrue(withWrites.contains("fw验证"), "验证码那条也要说明 —— 它是注册流程的一环");
        assertTrue(withWrites.contains("fw买个"), "手机下单那条路要给出写法，否则没人知道怎么买：" + withWrites);

        String readOnly = QqReplyText.help(false, false, null);
        assertFalse(readOnly.contains("fw开门"), "列出来却发不动，用户只会以为机器人坏了");
        assertFalse(readOnly.contains("fw结账"));
        assertFalse(readOnly.contains("fw买个"), "商品下单也是写指令，同样要藏起来");
        assertTrue(readOnly.contains("fw在店"), "只读指令照常列出");
    }

    @Test
    @DisplayName("帮助：常用的几条指令都要列出来，否则没人知道有")
    void help_listsKeyCommands() {
        String text = QqReplyText.help(true, true, null);

        // 只断言【指令名在不在】—— 那是功能可见性，指令名不会随文案改；
        // 分组标题、措辞那些属排版，不在这里锁
        assertTrue(text.contains("fw月卡"), "月卡说明要列出来，否则没人知道有这条：" + text);
        assertTrue(text.contains("fw价格"), "计费规则同理：" + text);
        assertTrue(text.contains("fw菜单"), "商品目录同理：" + text);
        assertTrue(text.contains("fw公告"), "公告查询要列出来，否则没人知道有：" + text);
        assertTrue(text.contains("fw买n个可乐"),
                "下单那条要给出可变数量的写法 —— 只给「买个」的话，"
                        + "想买多个的人不知道该怎么写：" + text);
        assertTrue(text.contains("也可直接发 ping"),
                "自检那条要写明免前缀的写法 —— 没写的话没人知道可以直接发 ping：" + text);
    }

    @Test
    @DisplayName("帮助：调整库存那条只对管理员列出")
    void help_listsStockAdjustOnlyForAdmin() {
        String admin = QqReplyText.help(true, true, null);
        assertTrue(admin.contains("fw可乐5"), "管理员要能看到这条，否则没人知道有：" + admin);
        assertTrue(admin.contains("仅管理员"), "标注清楚它只对管理员开放：" + admin);

        String normal = QqReplyText.help(true, false, null);
        assertFalse(normal.contains("fw可乐5"),
                "它对普通顾客本来就发不动（执行时会拒绝），列出来只会让人问「为什么我不能用」：" + normal);

        String readOnly = QqReplyText.help(false, true, null);
        assertFalse(readOnly.contains("fw可乐5"),
                "写指令总开关关掉时管理员也不该看到 —— 发了没有任何反应：" + readOnly);
    }

    // ==================================================================
    // 商品下单
    // ==================================================================

    @Test
    @DisplayName("网页端地址：拆成两条，第二条只有网址（方便复制）")
    void web_givesAddressAsConfigured() {
        List<String> messages = QqReplyText.web("https://uspace.example.com");

        assertEquals(2, messages.size(), "一条讲解、一条纯网址 —— 网址单独一条才好长按复制");
        assertEquals("https://uspace.example.com", messages.get(1),
                "⚠️ 第二条必须【只有网址】：混进「网页端：」这类前缀的话，"
                        + "长按复制会连前缀一起带走，粘到浏览器里就打不开了");
    }

    @Test
    @DisplayName("网页端地址：没配置时给一句人话，而不是空白")
    void web_handlesMissingConfig() {
        List<String> missing = QqReplyText.web(null);
        assertFalse(missing.isEmpty(), "没配置也要回话，不能一条都不发");
        assertFalse(missing.get(0).isBlank(), "那一条也不能是空白");

        List<String> blank = QqReplyText.web("   ");
        assertFalse(blank.isEmpty(), "只有空白也算没配置，同样要回话");
        assertFalse(blank.get(0).isBlank());
    }

    @Test
    @DisplayName("商品下单：报名称、数量、合计与单号，并说明到店自取")
    void productOrdered_reportsWhatWasBought() {
        ProductOrderVo order = new ProductOrderVo();
        order.setOrderNo("PD202610040127111234");
        order.setProductName("可乐 500ml");
        order.setQuantity(2);
        order.setAmount(new BigDecimal("7.00"));

        String text = QqReplyText.productOrdered(order, true, "http://x/#/product-orders");

        assertTrue(text.contains("可乐 500ml ×2"), text);
        assertTrue(text.contains("¥7.00"), "金额保留两位小数：" + text);
        assertTrue(text.contains("PD202610040127111234"), text);
        assertTrue(text.contains("http://x/#/product-orders"), "付款入口不能少：" + text);
        assertTrue(text.contains("付款截图直接发送到群里"), "群内传图那条路的入口要说明：" + text);
        assertTrue(text.contains("付款后请自行取货"),
                "无人值守店里没有店员，不写这一句他会站在店里等店员递给他：" + text);
    }

    @Test
    @DisplayName("商品下单：金额开关关掉时不报数，单号与入口照给")
    void productOrdered_hidesAmountWhenDisabled() {
        ProductOrderVo order = new ProductOrderVo();
        order.setOrderNo("PD202610040127111234");
        order.setProductName("可乐 500ml");
        order.setQuantity(2);
        order.setAmount(new BigDecimal("7.00"));

        String text = QqReplyText.productOrdered(order, false, "http://x/#/product-orders");

        assertFalse(text.contains("7.00"), "⚠️ 回复是群消息，金额开关关掉时一个数都不该出现：" + text);
        assertTrue(text.contains("PD202610040127111234"), text);
        assertTrue(text.contains("http://x/#/product-orders"), text);
    }

    @Test
    @DisplayName("商品名对不上：把名字原样回显，并指去 fw菜单")
    void productNotFound_echoesTheName() {
        String text = QqReplyText.productNotFound("可乐500ml");

        assertTrue(text.contains("可乐500ml"),
                "原样回显他打的那个名字，他才看得出自己少打了一个空格：" + text);
        assertTrue(text.contains("fw菜单"), "要给一个能做的动作，而不是一句「不存在」：" + text);
    }

    @Test
    @DisplayName("商品下单失败：售罄 / 下架 / 数量不对，各给一个能做的动作")
    void productOrderFailure_mapsErrorCodes() {
        assertTrue(QqReplyText.productOrderFailure(
                        ErrorCode.PRODUCT_SOLD_OUT, "该商品仅剩 2 件", "http://x")
                .contains("仅剩 2 件"), "服务端算出来的剩余量要带给用户，他才知道能买几件");
        assertTrue(QqReplyText.productOrderFailure(
                ErrorCode.PRODUCT_STATUS_INVALID, null, "http://x").contains("已下架"));
        assertTrue(QqReplyText.productOrderFailure(
                        ErrorCode.PARAM_INVALID, "单次最多买 99 件、至少 1 件", "http://x")
                .contains("99"), "范围提示要原样带出来");
        assertTrue(QqReplyText.productOrderFailure(null, "服务端炸了", "u").contains("服务端炸了"),
                "未知错误码时要把服务端给的话带出来");
    }

    // ==================================================================
    // 调整库存
    // ==================================================================

    @Test
    @DisplayName("调整库存：报出「原 → 新」，并把商品名原样回显")
    void stockAdjusted_reportsBeforeAndAfter() {
        String text = QqReplyText.stockAdjusted("可乐 500ml", 20, 2, 2);

        assertTrue(text.contains("可乐 500ml"),
                "名字要原样回显 —— 他才能看出这个名字切得对不对：" + text);
        assertTrue(text.contains("20 件"),
                "⚠️ 原值必须报：把「补货到 20」手快发成「fw可乐2」这种错，"
                        + "全靠这一句当场发现 —— 不报的话要等下次清点才知道：" + text);
        assertTrue(text.contains("→ 2 件"), "新值：" + text);
    }

    @Test
    @DisplayName("调整库存：有未付款订单占着货时，把可售量也说出来")
    void stockAdjusted_mentionsAvailabilityOnlyWhenDifferent() {
        String occupied = QqReplyText.stockAdjusted("可乐", 5, 5, 3);
        assertTrue(occupied.contains("可售 3"),
                "不说这一句，管理员会奇怪「明明设成 5 了，顾客怎么还说买不了」：" + occupied);

        String clear = QqReplyText.stockAdjusted("可乐", 5, 5, 5);
        assertFalse(clear.contains("可售"),
                "两者相同时不重复报同一个数 —— 那只会让人多读一行：" + clear);
    }

    @Test
    @DisplayName("调整库存：非管理员要被告知「能改发什么」")
    void stockAdjustForbidden_pointsToTheBuyCommand() {
        String text = QqReplyText.stockAdjustForbidden();

        assertTrue(text.contains("仅限管理员"), text);
        assertTrue(text.contains("fw可乐-2"),
                "⚠️ 发这条的多半是想买可乐、漏打了横杠的顾客 —— "
                        + "只回一句「仅限管理员」，他不知道自己该发什么：" + text);
    }

    @Test
    @DisplayName("认不出指令：给一个能做的动作，而不是一句「不明白」")
    void unknownCommand_givesAnAction() {
        String text = QqReplyText.unknownCommand();

        assertTrue(text.contains("无法识别该指令"), text);
        assertTrue(text.contains("fw帮助"), "要说清下一步能做什么：" + text);
    }

    // ==================================================================
    // 写指令的回复
    // ==================================================================

    @Test
    @DisplayName("开门：新建订单时不重复说「已开始计时」（播报已经说了）")
    void openPasscode_doesNotRepeatBroadcast() {
        String text = QqReplyText.openPasscode("123456", true, 0, false, "http://x/#/orders/1");

        assertFalse(text.contains("已开始计时"), "到店播报已经说过一次，这里再说就是两条重复消息：" + text);
        assertTrue(text.contains("门锁密码：123456"), text);
        assertTrue(text.contains("仅可使用一次"), text);
        assertTrue(text.contains("http://x/#/orders/1"), "固定密码的兜底指路不能少");
    }

    @Test
    @DisplayName("开门：已有订单时要补一句状态（那时没有播报）")
    void openPasscode_reportsStatusWhenNoBroadcast() {
        String text = QqReplyText.openPasscode("123456", false, 80, false, "http://x/#/orders/1");

        assertTrue(text.contains("你已在计时中（1 小时 20 分）"), text);
    }

    @Test
    @DisplayName("开门：私聊开关关着时，文案绝不能还承诺「已私聊发你」")
    void openPasscode_respectsPrivatePasscodeSwitch() {
        String off = QqReplyText.openPasscode("123456", true, 0, false, "http://x/#/orders/1");
        String on = QqReplyText.openPasscode("123456", true, 0, true, "http://x/#/orders/1");

        assertFalse(off.contains("已私聊发你"),
                "⚠️ 开关关着还写「已私聊发你」的话，用户会去翻一个永远空的私聊窗口：" + off);
        assertTrue(off.contains("请前往网页端查看"), off);
        assertTrue(on.contains("已通过私聊发送"), on);
    }

    @Test
    @DisplayName("开门失败：每个错误码都要给一个能做的动作")
    void openFailure_mapsErrorCodes() {
        assertTrue(QqReplyText.openFailure(ErrorCode.STORE_CLOSED, null, "u").contains("暂停营业"));
        assertTrue(QqReplyText.openFailure(ErrorCode.BOOKING_ACCESS_DENIED, null, "u").contains("包场"));
        assertTrue(QqReplyText.openFailure(ErrorCode.ORDER_UNPAID_EXISTS, null, "http://x")
                .contains("http://x"), "欠费拦截要给去付款的入口，不能只说一句「不行」");
        assertTrue(QqReplyText.openFailure(ErrorCode.LOCK_CLOUD_UNAVAILABLE, null, "u")
                .contains("门锁云"));
        assertTrue(QqReplyText.openFailure(null, "服务端炸了", "u").contains("服务端炸了"),
                "未知错误码时要把服务端给的话带出来");
    }

    @Test
    @DisplayName("结账：应付为 0 时说「已结清」；金额被关掉时只说去哪付")
    void settleDone_threeBranches() {
        assertTrue(QqReplyText.settleDone(BigDecimal.ZERO, true, "u").contains("无需支付"));
        assertTrue(QqReplyText.settleDone(new BigDecimal("12"), true, "http://x/settle")
                .contains("¥12.00"), "金额保留两位小数");
        assertFalse(QqReplyText.settleDone(new BigDecimal("12"), false, "http://x/settle")
                        .contains("12"),
                "⚠️ 金额开关关掉时不能报数 —— 回复是群消息，全群可见");
        assertTrue(QqReplyText.settleDone(new BigDecimal("12"), true, "u").contains("付款截图直接发送到群里"),
                "「群内传图」那条路的入口只在这里说明，少了它没人会想到可以发图");
    }

    @Test
    @DisplayName("结账（已停过表）：把待付金额与入口重新给一遍，不是回一句「没有在计时的订单」")
    void settleAlreadyStopped_repeatsPendingPayment() {
        String text = QqReplyText.settleAlreadyStopped(
                "OD202610040116074492", new BigDecimal("332.5"), true, "http://x/#/orders/2516");

        assertTrue(text.contains("¥332.50"), "金额保留两位小数：" + text);
        assertTrue(text.contains("OD202610040116074492"), text);
        assertTrue(text.contains("http://x/#/orders/2516"),
                "这是「截图等超时了」之后唯一的回头路，没有入口这条回复就白说了：" + text);
        assertTrue(text.contains("付款截图直接发送到群里"), "回头路上要再说明一次可以发图：" + text);
    }

    @Test
    @DisplayName("结账（已停过表）：金额开关关掉时不报数，但入口照给")
    void settleAlreadyStopped_hidesAmountWhenDisabled() {
        String text = QqReplyText.settleAlreadyStopped(
                "OD202610040116074492", new BigDecimal("332.5"), false, "http://x/#/orders/2516");

        assertFalse(text.contains("332"), "⚠️ 回复是群消息，金额开关关掉时一个数都不该出现：" + text);
        assertTrue(text.contains("http://x/#/orders/2516"), text);
    }

    // ==================================================================
    // 看看自己
    // ==================================================================

    @Test
    @DisplayName("看看自己：月卡、消费、时长、待付款、偏好都报出来，且绝不出现密码")
    void me_reportsEverything() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setNickname("张三");
        user.setTotalPaid(new BigDecimal("1234.5"));

        MonthlyCard card = new MonthlyCard();
        card.setCardType("ALL_DAY");
        card.setStartDate(LocalDate.of(2026, 10, 1));
        card.setEndDate(LocalDate.of(2026, 10, 30));

        MonthSpentVo month = new MonthSpentVo();
        month.setMonthSpent(new BigDecimal("128"));
        month.setRemaining(new BigDecimal("72"));

        OrderStatsVo stats = new OrderStatsVo();
        stats.setTotalMinutes(750);
        stats.setMonthMinutes(200);

        OrderVo pending = new OrderVo();
        pending.setStatus(OrderStatus.PENDING_PAYMENT.name());
        pending.setOrderNo("OD202610040116074492");
        pending.setPayableAmount(new BigDecimal("3"));

        String text = QqReplyText.me(user, card, month, stats, pending, "拍拍机", null, true);

        assertTrue(text.startsWith("张三"), text);
        assertTrue(text.contains("全天月卡"), text);
        assertTrue(text.contains("10月30日"), text);
        assertTrue(text.contains("¥128.00"), text);
        assertTrue(text.contains("再消费 ¥72.00"), text);
        assertTrue(text.contains("¥1234.50"), text);
        assertTrue(text.contains("累计在店 12 小时 30 分（本月 3 小时 20 分）"), text);
        assertTrue(text.contains("有一笔未付款订单：¥3.00"), text);
        assertTrue(text.contains("OD202610040116074492"), text);
        assertTrue(text.contains("偏好：拍拍机"), text);
        assertFalse(text.contains("密码"), "这条回复是群消息，里头绝不能出现密码");
    }

    @Test
    @DisplayName("看看自己：⚠️ 进行中的那一单不算「还没付款」")
    void me_doesNotCallActiveOrderUnpaid() {
        OrderVo active = new OrderVo();
        active.setStatus(OrderStatus.IN_USE.name());
        active.setOrderNo("OD202610040102369492");

        String text = QqReplyText.me(namedUser(), null, null, null, active, null, null, true);

        assertFalse(text.contains("还没付款"),
                "⚠️ findUnsettledOrder 把进行中的那一单也返回，不过滤就会报「你有一笔没付款」——"
                        + "而人正玩着，他只会一脸问号：" + text);
    }

    @Test
    @DisplayName("看看自己：在店时报实时状态，不在店时那两行都不出现")
    void me_reportsCurrentSessionOnlyWhenInStore() {
        OrderPreviewVo preview = new OrderPreviewVo();
        preview.setStayMinutes(80);
        preview.setBill(billOf("12"));

        String inStore = QqReplyText.me(namedUser(), null, null, null, null, null, preview, true);
        assertTrue(inStore.contains("当前在店 1 小时 20 分，预计 ¥12.00"), inStore);
        assertTrue(inStore.contains("fw当前订单"), "告诉他还有更详细的可以看：" + inStore);

        String notInStore = QqReplyText.me(namedUser(), null, null, null, null, null, null, true);
        assertFalse(notInStore.contains("当前在店"), notInStore);
    }

    @Test
    @DisplayName("看看自己：金额关掉时只报状态，不报数")
    void me_hidesAmountsWhenDisabled() {
        MonthSpentVo month = new MonthSpentVo();
        month.setMonthSpent(new BigDecimal("128"));

        OrderStatsVo stats = new OrderStatsVo();
        stats.setTotalMinutes(750);

        OrderVo pending = new OrderVo();
        pending.setStatus(OrderStatus.PENDING_PAYMENT.name());
        pending.setOrderNo("OD1");
        pending.setPayableAmount(new BigDecimal("3"));

        String text = QqReplyText.me(namedUser(), null, month, stats, pending, null, null, false);

        assertFalse(text.contains("¥"), "金额开关关掉时一个数都不该出现：" + text);
        assertTrue(text.contains("张三"), text);
        assertTrue(text.contains("累计在店 12 小时 30 分"), "时长不是金额，照报：" + text);
        assertTrue(text.contains("OD1"), "单号不是金额，照报：" + text);
    }

    // ==================================================================
    // 当前订单（只预览，不停表）
    // ==================================================================

    @Test
    @DisplayName("当前订单：报单号、进场、已玩、应付与跳档预告，并说明只看不停表")
    void currentOrder_showsPreview() {
        OrderPreviewVo preview = new OrderPreviewVo();
        preview.setOrderNo("OD202610040102369492");
        preview.setStartTime(LocalDateTime.of(2026, 10, 4, 21, 40));
        preview.setStayMinutes(80);
        preview.setBill(billOf("9"));
        preview.setNextChangeInSeconds(750L);
        preview.setNextChangeText("进入下一档 ¥12.00");

        String text = QqReplyText.currentOrder(preview, true);

        assertTrue(text.contains("OD202610040102369492"), text);
        assertTrue(text.contains("进场 10月4日 21:40"), text);
        assertTrue(text.contains("已玩 1 小时 20 分"), text);
        assertTrue(text.contains("当前应付 ¥9.00"), text);
        assertTrue(text.contains("还有 12 分 30 秒，进入下一档 ¥12.00"), text);
        assertTrue(text.contains("计时照常进行"),
                "⚠️ 不写这句，用户会以为发一条指令就把账结了，然后接着玩：" + text);
        assertTrue(text.contains("fw结账"), "要指路到真正结账的那一条：" + text);
    }

    @Test
    @DisplayName("当前订单：⚠️ 全段被包场覆盖时，0 元单不引导去付款")
    void currentOrder_handlesZeroBill() {
        OrderPreviewVo preview = new OrderPreviewVo();
        preview.setOrderNo("OD1");
        preview.setStartTime(LocalDateTime.of(2026, 10, 4, 21, 40));
        preview.setStayMinutes(80);
        preview.setBill(billOf("0"));
        preview.setFreeByBooking(true);
        preview.setWillAutoSettle(true);

        String text = QqReplyText.currentOrder(preview, true);

        assertTrue(text.contains("无需支付"), text);
        assertFalse(text.contains("当前应付"),
                "0 元单没有可付的通道，说「应付 ¥0.00」会把人引去付款页：" + text);
        assertTrue(text.contains("包场时段不计费"), text);
    }

    @Test
    @DisplayName("当前订单：金额关掉时不报数，但跳档预告里的档位价照报")
    void currentOrder_hidesAmountButKeepsPriceTier() {
        OrderPreviewVo preview = new OrderPreviewVo();
        preview.setOrderNo("OD1");
        preview.setStartTime(LocalDateTime.of(2026, 10, 4, 21, 40));
        preview.setStayMinutes(10);
        preview.setBill(billOf("4"));
        preview.setNextChangeInSeconds(300L);
        preview.setNextChangeText("进入下一档 ¥8.00");

        String text = QqReplyText.currentOrder(preview, false);

        assertFalse(text.contains("当前应付"), "金额开关管的是【他自己的消费额】：" + text);
        assertTrue(text.contains("进入下一档 ¥8.00"),
                "档位价是价目表上的数，群里任何人发 fw价格 都查得到，不受这个开关管：" + text);
    }

    @Test
    @DisplayName("没有在计时的单：给待付款那一笔，但不提「截图发群里」")
    void noActiveOrder_pointsToWebOnly() {
        String text = QqReplyText.noActiveOrder("OD1", new BigDecimal("3"), true,
                "http://x/#/orders");

        assertTrue(text.contains("没有正在计时的订单"), text);
        assertTrue(text.contains("¥3.00"), text);
        assertTrue(text.contains("http://x/#/orders"), text);
        assertFalse(text.contains("截图"),
                "⚠️「截图发群里」是发过 fw结账 才有的待遇（那道闸要撑起来）——"
                        + "这里说了，用户会发一张石沉大海的图：" + text);
    }

    // ==================================================================
    // 未付款清单与取消（2026-10-10）
    // ==================================================================

    @Test
    @DisplayName("未付款清单：四类都列出来，状态里带下一步动作")
    void unpaidBills_listsAllTypes() {
        List<UnpaidBill> bills = List.of(
                new UnpaidBill("计时", "OD202610101200001234", new BigDecimal("8.00"), "待支付", false),
                new UnpaidBill("包场", "BK202610101200005678", new BigDecimal("60.00"), "待付款", true),
                new UnpaidBill("商品", "PD202610101200009012", new BigDecimal("3.50"),
                        "凭证未通过，请重新上传付款截图", true));

        String text = QqReplyText.unpaidBills(bills, true, "http://localhost:5173/#/orders");

        assertTrue(text.contains("3 笔"), text);
        assertTrue(text.contains("OD202610101200001234"), text);
        assertTrue(text.contains("BK202610101200005678"), text);
        assertTrue(text.contains("PD202610101200009012"), text);
        assertTrue(text.contains("¥60.00"), text);
        assertTrue(text.contains("凭证未通过，请重新上传付款截图"),
                "状态要带下一步动作，否则用户知道出了事但不知道做什么：" + text);
        assertTrue(text.contains("fw取消"), "有可取消的单子时要给出取消入口：" + text);
    }

    @Test
    @DisplayName("未付款清单：全是计时订单时不提取消（欠费不能自消，那条提示是空话）")
    void unpaidBills_omitsCancelHintWhenNothingCancelable() {
        List<UnpaidBill> bills = List.of(
                new UnpaidBill("计时", "OD202610101200001234", new BigDecimal("8.00"), "待支付", false));

        String text = QqReplyText.unpaidBills(bills, true, "http://localhost:5173/#/orders");

        assertFalse(text.contains("fw取消"),
                "⚠️ 提示了却没一条能取消 —— 用户照做只会被拒，那比不提更糟：" + text);
    }

    @Test
    @DisplayName("未付款清单：没有未付款单时回一句人话")
    void unpaidBills_empty() {
        String text = QqReplyText.unpaidBills(List.of(), true, "http://localhost:5173/#/orders");

        assertTrue(text.contains("没有"), text);
    }

    @Test
    @DisplayName("未付款清单：金额开关关掉时不报数，单号照给")
    void unpaidBills_hidesAmountsWhenDisabled() {
        List<UnpaidBill> bills = List.of(
                new UnpaidBill("计时", "OD202610101200001234", new BigDecimal("8.00"), "待支付", false));

        String text = QqReplyText.unpaidBills(bills, false, "http://localhost:5173/#/orders");

        assertFalse(text.contains("8.00"), "金额关掉时不该出现数字（群消息全群可见）：" + text);
        assertTrue(text.contains("OD202610101200001234"), "单号要留着 —— 下一步动作靠它：" + text);
    }

    @Test
    @DisplayName("取消：成功文案报出释放了什么；计时订单的拒绝文案指路 fw结账")
    void cancelDoneAndRefused() {
        String done = QqReplyText.cancelDone("商品", "PD202610101200009012", "占用的库存已释放");
        assertTrue(done.contains("已取消"), done);
        assertTrue(done.contains("PD202610101200009012"), done);
        assertTrue(done.contains("库存已释放"), done);

        String refused = QqReplyText.cancelOrderRefused("http://localhost:5173/#/orders");
        assertTrue(refused.contains("不支持"), refused);
        assertTrue(refused.contains("fw结账"),
                "「不行」之外必须指路，否则用户下一步无从下手：" + refused);
    }

    @Test
    @DisplayName("取消：失败文案按错误码分两档（找不到 vs 状态不对）")
    void cancelFailed_splitsByErrorCode() {
        String notFound = QqReplyText.cancelFailed(ErrorCode.PRODUCT_ORDER_NOT_FOUND, null);
        assertTrue(notFound.contains("没找到"), notFound);
        assertTrue(notFound.contains("fw未付款"), "要指路去核对单号：" + notFound);

        String wrongState = QqReplyText.cancelFailed(
                ErrorCode.BOOKING_NOT_EDITABLE, "该购买单不是待支付状态，无法取消");
        assertTrue(wrongState.contains("不支持取消"), wrongState);
        assertTrue(wrongState.contains("不是待支付状态"), "服务端给的原因要转述出来：" + wrongState);
    }

    // ==================================================================
    // 造数据的小工具
    // ==================================================================

    /**
     * 一个带昵称的用户。
     *
     * @return 用户实体
     */
    private static SysUser namedUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setNickname("张三");
        return user;
    }

    /**
     * 造一份只填了总额的账单。
     *
     * @param amount 总金额（元）
     * @return 账单
     */
    private static BillingResult billOf(String amount) {
        BillingResult bill = new BillingResult();
        bill.setTotalAmount(new BigDecimal(amount));
        return bill;
    }

    /**
     * 造一台机台的陈列视图。
     *
     * <p>状况直接给中文（{@code statusLabel}）—— 那是后端按枚举翻好的，
     * 这里不重复走一遍枚举（那属于模块 4 的测试）。
     *
     * @param name     机台名
     * @param label    状况中文
     * @param location 位置，可为 null
     * @return 陈列视图
     */
    private static DeviceDisplayVo deviceDisplay(String name, String label, String location) {
        DeviceDisplayVo device = new DeviceDisplayVo();
        device.setName(name);
        device.setStatusLabel(label);
        device.setLocation(location);
        return device;
    }

    /**
     * 造一个机台类型分组。
     *
     * @param typeCode 类型代码
     * @param typeName 类型中文名
     * @param devices  组内机台
     * @return 分组视图
     */
    private static DeviceGroupVo deviceGroup(String typeCode, String typeName,
                                             List<DeviceDisplayVo> devices) {
        DeviceGroupVo group = new DeviceGroupVo();
        group.setTypeCode(typeCode);
        group.setTypeName(typeName);
        group.setDevices(devices);
        return group;
    }

    /**
     * 造一条公告视图。
     *
     * @param title     标题
     * @param content   正文，可为 null（自动公告没有正文）
     * @param createdAt 发布时刻
     * @return 公告视图
     */
    private static NoticeVo notice(String title, String content, LocalDateTime createdAt) {
        NoticeVo vo = new NoticeVo();
        vo.setTitle(title);
        vo.setContent(content);
        vo.setCreatedAt(createdAt);
        return vo;
    }
}
