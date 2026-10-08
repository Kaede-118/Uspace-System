package com.kaede.uspace.qqbot;

import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.BillingRulesVo;
import com.kaede.uspace.billing.event.FreePeriodChangeAction;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.notice.NoticePublishMode;
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
        assertEquals("现在店里没人。", QqReplyText.instore(List.of(), Map.of(), 30));
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
        assertEquals("近期没有包场安排，随时可以来。",
                QqReplyText.bookingSchedule(List.of(), Duration.ofMinutes(15), LocalDate.of(2026, 10, 4)));
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
    @DisplayName("包场播报：日期用相对说法，时段写法与 /包场 指令逐字一致")
    void bookingActivated_usesScheduleFormat() {
        String text = QqReplyText.bookingActivated(
                LocalDateTime.of(2026, 10, 5, 14, 0),
                LocalDateTime.of(2026, 10, 5, 18, 0),
                LocalDate.of(2026, 10, 4));

        assertEquals("📅 已安排包场：明天 14:00 – 18:00，该时段仅限包场人与被邀请者入场。", text,
                "与 /包场 指令同一套日期与时刻写法 —— 两处不一致的话，"
                        + "同一场包场在群里会有两种说法");
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
        assertTrue(text.contains("还剩 3"), "「还剩多少」报的是可售量，与商城页、/菜单 同一口径：" + text);
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

        assertEquals("🚧 门店停业安排：明天 10:00 – 14:00（设备维护），该时段不接待新顾客。", text);
    }

    @Test
    @DisplayName("停业播报：撤销带原时段、不重复原因 —— 撤的是哪一段要认得出")
    void closureChanged_deletedCarriesRange() {
        String text = QqReplyText.closureChanged(ClosureChangeAction.DELETED,
                LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 14, 0),
                "设备维护", LocalDate.of(2026, 10, 4));

        assertEquals("🚧 停业安排已撤销：明天 10:00 – 14:00，该时段恢复正常接待。", text);
    }

    @Test
    @DisplayName("免费活动播报：没填名称时不印出空括号")
    void freePeriodChanged_createdWithoutReason() {
        String text = QqReplyText.freePeriodChanged(FreePeriodChangeAction.CREATED,
                LocalDateTime.of(2026, 10, 5, 20, 0),
                LocalDateTime.of(2026, 10, 5, 22, 0),
                null, LocalDate.of(2026, 10, 4));

        assertEquals("🎉 免费活动：明天 20:00 – 22:00，该时段内消费全免。", text,
                "名称是选填的，没填时不能印出「（）」—— 那像是系统出了错");
    }

    @Test
    @DisplayName("免费活动播报：撤销明说「恢复按时长计费」")
    void freePeriodChanged_deleted() {
        String text = QqReplyText.freePeriodChanged(FreePeriodChangeAction.DELETED,
                LocalDateTime.of(2026, 10, 5, 20, 0),
                LocalDateTime.of(2026, 10, 5, 22, 0),
                "周年庆", LocalDate.of(2026, 10, 4));

        assertEquals("🎉 免费活动已撤销：明天 20:00 – 22:00，该时段恢复按时长计费。", text);
    }

    @Test
    @DisplayName("包场撤销播报：时段写法与「已安排包场」逐字同一套")
    void bookingRevoked_matchesScheduleFormat() {
        String text = QqReplyText.bookingRevoked(
                LocalDateTime.of(2026, 10, 5, 14, 0),
                LocalDateTime.of(2026, 10, 5, 18, 0),
                LocalDate.of(2026, 10, 4));

        assertEquals("📅 包场已撤销：明天 14:00 – 18:00，该时段恢复开放。", text,
                "要与几天前那条生效消息对得上，否则读不出这两条说的是同一场包场");
    }

    @Test
    @DisplayName("公告播报：手写公告带正文与详情链接")
    void noticePublished_manualCarriesContentAndLink() {
        String text = QqReplyText.noticePublished(NoticePublishMode.MANUAL,
                "本周六场地维护", "10:00–14:00 暂停营业", "http://host/#/notices");

        assertEquals("📢 门店公告：本周六场地维护\n10:00–14:00 暂停营业\n详情 → http://host/#/notices",
                text);
    }

    @Test
    @DisplayName("公告播报：手写公告没填正文时只发标题与链接")
    void noticePublished_manualWithoutContent() {
        String text = QqReplyText.noticePublished(NoticePublishMode.MANUAL,
                "仅标题", null, "http://host/#/notices");

        assertEquals("📢 门店公告：仅标题\n详情 → http://host/#/notices", text,
                "正文是选填的，没填时不该留一个空行");
    }

    @Test
    @DisplayName("公告播报：自动公告只发标题 —— 标题本身已是完整的事件描述")
    void noticePublished_autoIsTitleOnly() {
        String text = QqReplyText.noticePublished(NoticePublishMode.AUTO,
                "拍拍机 1 号 由 良好 转为 维护中", null, "http://host/#/notices");

        assertEquals("📢 拍拍机 1 号 由 良好 转为 维护中", text);
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

        String text = QqReplyText.menu(List.of(water, chips), "http://x/#/mall");

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
        assertEquals("店里暂时没有上架的商品。",
                QqReplyText.menu(List.of(), "http://x/#/mall"));
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
        assertEquals("月卡暂时没有开放售卖，问一下店主吧。",
                QqReplyText.cardTypes(List.of(), "http://x/#/cards"));
    }

    // ==================================================================
    // 帮助
    // ==================================================================

    @Test
    @DisplayName("帮助：写明前缀要求，写指令关掉时不列出那两条")
    void help_mentionsPrefixAndHidesDisabledWrites() {
        String withWrites = QqReplyText.help(true);
        assertTrue(withWrites.contains("/ 或 fw"), "必须写清前缀要求，否则用户裸发指令什么都得不到");
        assertTrue(withWrites.contains("/开门"), withWrites);
        assertTrue(withWrites.contains("/结账"), withWrites);
        assertTrue(withWrites.contains("/验证"), "验证码那条也要说明 —— 它是注册流程的一环");
        assertTrue(withWrites.contains("/买个"), "手机下单那条路要给出写法，否则没人知道怎么买：" + withWrites);

        String readOnly = QqReplyText.help(false);
        assertFalse(readOnly.contains("/开门"), "列出来却发不动，用户只会以为机器人坏了");
        assertFalse(readOnly.contains("/结账"));
        assertFalse(readOnly.contains("/买个"), "商品下单也是写指令，同样要藏起来");
        assertTrue(readOnly.contains("/在店"), "只读指令照常列出");
    }

    @Test
    @DisplayName("帮助：按「你想干什么」分组，四段标题都在")
    void help_isGroupedByPurpose() {
        String text = QqReplyText.help(true);

        assertTrue(text.contains("【看店里】"), "指令到十来条之后，不分组就得逐行读完才能找到要的那条：" + text);
        assertTrue(text.contains("【我自己的】"), text);
        assertTrue(text.contains("【常用】"), text);
        assertTrue(text.contains("【其他】"), text);
        assertTrue(text.contains("/月卡"), "月卡说明要列出来，否则没人知道有这条：" + text);
    }

    // ==================================================================
    // 商品下单
    // ==================================================================

    @Test
    @DisplayName("网页端地址：原样给出配置里那一项，不加工")
    void web_givesAddressAsConfigured() {
        assertEquals("网页端：https://uspace.example.com\n下单、查看账单、买月卡都在这里。",
                QqReplyText.web("https://uspace.example.com"),
                "地址原样来自配置 —— 加工过一次的话，群里看到的与配置里那一项就对不上了");
    }

    @Test
    @DisplayName("网页端地址：没配置时给一句人话，而不是空白")
    void web_handlesMissingConfig() {
        assertTrue(QqReplyText.web(null).contains("还没配置"));
        assertTrue(QqReplyText.web("   ").contains("还没配置"));
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
        assertTrue(text.contains("付款截图发到群里"), "群内传图那条路的入口要说明：" + text);
        assertTrue(text.contains("到店自取"),
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
    @DisplayName("商品名对不上：把名字原样回显，并指去 /菜单")
    void productNotFound_echoesTheName() {
        String text = QqReplyText.productNotFound("可乐500ml");

        assertTrue(text.contains("可乐500ml"),
                "原样回显他打的那个名字，他才看得出自己少打了一个空格：" + text);
        assertTrue(text.contains("/菜单"), "要给一个能做的动作，而不是一句「不存在」：" + text);
    }

    @Test
    @DisplayName("商品下单失败：售罄 / 下架 / 数量不对，各给一个能做的动作")
    void productOrderFailure_mapsErrorCodes() {
        assertTrue(QqReplyText.productOrderFailure(
                        ErrorCode.PRODUCT_SOLD_OUT, "该商品仅剩 2 件", "http://x")
                .contains("仅剩 2 件"), "服务端算出来的剩余量要带给用户，他才知道能买几件");
        assertTrue(QqReplyText.productOrderFailure(
                ErrorCode.PRODUCT_STATUS_INVALID, null, "http://x").contains("不卖了"));
        assertTrue(QqReplyText.productOrderFailure(
                        ErrorCode.PARAM_INVALID, "单次最多买 99 件、至少 1 件", "http://x")
                .contains("99"), "范围提示要原样带出来");
        assertTrue(QqReplyText.productOrderFailure(null, "服务端炸了", "u").contains("服务端炸了"),
                "未知错误码时要把服务端给的话带出来");
    }

    @Test
    @DisplayName("认不出指令：给一个能做的动作，而不是一句「不明白」")
    void unknownCommand_givesAnAction() {
        String text = QqReplyText.unknownCommand();

        assertTrue(text.contains("没认出这条指令"), text);
        assertTrue(text.contains("/帮助"), "要说清下一步能做什么：" + text);
    }

    // ==================================================================
    // 写指令的回复
    // ==================================================================

    @Test
    @DisplayName("开门：新建订单时不重复说「已开始计时」（播报已经说了）")
    void openPasscode_doesNotRepeatBroadcast() {
        String text = QqReplyText.openPasscode("123456", true, 0, "http://x/#/orders/1");

        assertFalse(text.contains("已开始计时"), "到店播报已经说过一次，这里再说就是两条重复消息：" + text);
        assertTrue(text.contains("门锁密码：123456"), text);
        assertTrue(text.contains("用一次即作废"), text);
        assertTrue(text.contains("http://x/#/orders/1"), "私聊失败时的兜底指路不能少");
    }

    @Test
    @DisplayName("开门：已有订单时要补一句状态（那时没有播报）")
    void openPasscode_reportsStatusWhenNoBroadcast() {
        String text = QqReplyText.openPasscode("123456", false, 80, "http://x/#/orders/1");

        assertTrue(text.contains("你已在计时中（1 小时 20 分）"), text);
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
        assertTrue(QqReplyText.settleDone(new BigDecimal("12"), true, "u").contains("付款截图发到群里"),
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
        assertTrue(text.contains("付款截图发到群里"), "回头路上要再说明一次可以发图：" + text);
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
        assertTrue(text.contains("累计在店 12 小时 30 分 · 本月 3 小时 20 分"), text);
        assertTrue(text.contains("有一笔还没付款：¥3.00"), text);
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
        assertTrue(inStore.contains("/当前订单"), "告诉他还有更详细的可以看：" + inStore);

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
        assertTrue(text.contains("计时照走"),
                "⚠️ 不写这句，用户会以为发一条指令就把账结了，然后接着玩：" + text);
        assertTrue(text.contains("/结账"), "要指路到真正结账的那一条：" + text);
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
                "档位价是价目表上的数，群里任何人发 /价格 都查得到，不受这个开关管：" + text);
    }

    @Test
    @DisplayName("没有在计时的单：给待付款那一笔，但不提「截图发群里」")
    void noActiveOrder_pointsToWebOnly() {
        String text = QqReplyText.noActiveOrder("OD1", new BigDecimal("3"), true,
                "http://x/#/orders");

        assertTrue(text.contains("没有在计时的订单"), text);
        assertTrue(text.contains("¥3.00"), text);
        assertTrue(text.contains("http://x/#/orders"), text);
        assertFalse(text.contains("截图"),
                "⚠️「截图发群里」是发过 /结账 才有的待遇（那道闸要撑起来）——"
                        + "这里说了，用户会发一张石沉大海的图：" + text);
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
}
