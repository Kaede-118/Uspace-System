package com.kaede.uspace.qqbot;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.order.PaymentProofImageService;
import com.kaede.uspace.order.PaymentProofService;
import com.kaede.uspace.order.PaymentTargetHandler;
import com.kaede.uspace.order.PaymentTargetType;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.order.dto.ProofImageVo;
import com.kaede.uspace.order.dto.ProofSubmitRequest;
import com.kaede.uspace.order.dto.ProofSubmitVo;
import com.kaede.uspace.qqbot.protocol.OneBotEvent;
import com.kaede.uspace.qqbot.protocol.OneBotImage;
import com.kaede.uspace.user.entity.SysUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群里的付款截图受理（模块 11）。
 *
 * <h3>它解决什么</h3>
 *
 * <p>扫码转账那条路要在<b>网页端</b>上传付款截图。但顾客付款时人就在群里
 * （{@code fw结账}、{@code fw可乐-2} 都是从群里发的），让他为了传张图再打开一次网页
 * 是白费一道手续。于是：<b>那两条指令之后，这个人发的下一张图就当付款截图收下</b>
 * —— 取图、识别、提交凭证，与网页那条路<b>走的是同一套服务</b>
 * （{@link PaymentProofImageService} 与 {@link PaymentProofService}），
 * 所以两条路不会分岔出两套规则。
 *
 * <h3>⚠️ 为什么必须有「等一张图」这道闸</h3>
 *
 * <p>绝不能让「群里发图」直接等于「提交付款凭证」。群里的图绝大多数是闲聊 ——
 * 表情包、随手拍的照、发错群 —— 而订单与商品的凭证是<b>提交即落账</b>的
 * （订单当场转已支付、商品当场扣库存）。任何一张无关的图被当成凭证，
 * 就是把一笔没收到钱的账标成已收，事后只能靠管理员人工发现。
 *
 * <p><b>那两条写指令就是那道闸</b>（{@code fw结账}、{@code fw商品名-数量}）：
 * 它们同时说明「这个人有意传凭证」与「这笔账正等着付」。
 * 闸门之外，本类对任何图片都不作声。
 *
 * <h3>认两类账：房间订单与商品</h3>
 *
 * <p>等待表里存的是 {@link PaymentTargetType} + 目标 ID，所以加一类收款
 * <b>本类一行都不用改</b> —— 状态校验与「单号、金额是多少」全部问
 * {@link PaymentTargetHandler#loadForProof}（那是模块 8 已有的策略，
 * 四类收款各有一个实现）。要加第三类时，只要那条路上有人调用 {@link #expect}。
 *
 * <h3>三道失效条件</h3>
 *
 * <ol>
 *   <li><b>网页端付过了</b> —— 每次受理前回查目标状态，不再是待支付就丢弃。
 *       这就是「走了网页途径就取消接受群内图片」的实现：不靠通知，靠<b>惰性校验</b></li>
 *   <li><b>等了太久</b> —— 超过 {@link #WAIT_WINDOW} 就作废（只留一句说明）</li>
 *   <li><b>只认第一张</b> —— 一次发多张时取第一张，不做「多图当成多张凭证」这种猜测</li>
 * </ol>
 *
 * <p>状态存在内存里（{@code ConcurrentHashMap}），与 {@code QqVerifyService} 同一手法：
 * 它是一份一次性的短期状态，不值得为它建表；进程重启后重新发一次那条指令即可。
 *
 * <h3>⚠️ 两个 ID 不是一回事</h3>
 *
 * <p>本类同时用到<b>两个不同的标识</b>，混起来不会报任何错，只会静默：
 *
 * <ul>
 *   <li><b>QQ 号</b>（如 {@code 2198047522}）—— 群事件里唯一给得出的身份，
 *       所以它是等待表的<b>键</b></li>
 *   <li><b>用户 ID</b>（{@code sys_user.id}，如 {@code 1249}）——
 *       载入目标时校验归属用的是它</li>
 * </ul>
 *
 * <p><b>这条是本类踩过的坑</b>：起初 {@code expect} 拿用户 ID 当键、
 * {@code handleImages} 拿事件里的 QQ 号去查，两边永远对不上 ——
 * 表现是「指令发完之后发图毫无反应」，而日志里一句话都没有
 * （查不到就走「没在等」的静默分支）。所以 {@link #expect} 直接收
 * {@link SysUser} 而不是两个 {@code Long}：<b>两个同类型的参数挨在一起，
 * 写反了编译照样过</b>，而实体身上哪个字段是什么是没有歧义的。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class QqPaymentProofService {

    /**
     * 等一张图的期限。
     *
     * <p>太短了用户来不及翻相册，太长了「几分钟前随口发的表情包」会被误收。
     * 5 分钟大致是「下单 → 打开付款 App → 截图 → 回到群里发出来」的正常耗时上限。
     */
    private static final Duration WAIT_WINDOW = Duration.ofMinutes(5);

    private final OneBotClient client;

    private final OneBotImageFetcher imageFetcher;

    private final PaymentProofImageService proofImageService;

    private final PaymentProofService paymentProofService;

    /**
     * 四类收款的处理器（模块 8 的策略），按 {@link PaymentTargetHandler#type()} 找。
     *
     * <p>用 {@code List} 注入而不是手工维护一张表 —— 与 {@code PaymentProofService}
     * 同一套做法：漏注册这种事不该靠人记得。
     */
    private final List<PaymentTargetHandler> handlers;

    private final Clock clock;

    /**
     * 谁在等发图：<b>QQ 号</b> → 待付的目标与登记时刻。
     *
     * <p>⚠️ <b>键必须是 QQ 号，不能是 {@code sys_user.id}</b> ——
     * 入站事件里拿得到的只有 {@code event.getUserId()}（QQ 号），
     * 而载入目标要的是用户 ID。两者是不同的数（见类注释），
     * 取错的表现是「发完指令再发图毫无反应」，且一行日志都没有。
     *
     * <p>用 {@code String} 而不是 {@code Long} 当键，与 {@code QqVerifyService} 的
     * 等待表一致，也免得为一个「理论上可能不是数字」的 QQ 号去解析整数。
     */
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

    public QqPaymentProofService(OneBotClient client,
                                OneBotImageFetcher imageFetcher,
                                PaymentProofImageService proofImageService,
                                PaymentProofService paymentProofService,
                                List<PaymentTargetHandler> handlers,
                                Clock clock) {
        this.client = client;
        this.imageFetcher = imageFetcher;
        this.proofImageService = proofImageService;
        this.paymentProofService = paymentProofService;
        this.handlers = handlers;
        this.clock = clock;
    }

    /**
     * 登记：让这个人接下来发的那张图当付款截图。
     *
     * <p>由两条写指令在目标建好之后调用（{@code fw结账} 与 {@code fw商品名-数量}）。
     * 重复调用以最后一次为准 —— 同一个人连着下两单，等的自然是最近那一笔。
     * 这是刻意的：群消息是线性的，他此刻要付的就是刚说的那笔。
     *
     * <p>⚠️ <b>收 {@link SysUser} 而不是两个 {@code Long}</b>：QQ 号当键、
     * 用户 ID 给载入目标用，两个参数挨在一起写反了<b>编译照样过</b> ——
     * 本类就是这么错过的（见类注释）。
     *
     * @param user       发指令的这个人，其 {@code qq} 是等待表的键、{@code id} 是载入目标用的
     * @param targetType 这是哪一类收款（房间订单 / 商品 / ……）
     * @param targetId   那笔待付目标的主键
     */
    public void expect(SysUser user, PaymentTargetType targetType, Long targetId) {
        waiting.put(user.getQq(), new Waiting(user.getId(), targetType, targetId,
                LocalDateTime.now(clock)));
        log.debug("[QQ机器人] 已登记等待付款截图 qq={} userId={} target={}#{}",
                user.getQq(), user.getId(), targetType, targetId);
    }

    /**
     * 收到一条<b>带图片的</b>群消息时调用。
     *
     * <p>调用方（{@code QqCommandService}）只在 {@code event.hasImage()} 时调它 ——
     * 图片消息不与指令那条路混在一起，因为图片消息压根解析不出指令。
     *
     * <p>⚠️ <b>每条分支都要有明确交代</b>（「执行必有回响」那条纪律）：
     * 收了图就回识别结果，取不到图就说取不到，已经付过就说明不再重复提交。
     * 唯一的静默是<b>根本没在等</b> —— 那是「确实没调用」，群里的休闲图不该被搭理。
     *
     * <p>⚠️ <b>查等待表用的键是发送者的 QQ 号</b>（事件里叫 {@code user_id}），
     * 必须与 {@link #expect} 写入的键一致 —— 见类注释里那条踩坑记录。
     * 而载入目标用的是记录里的用户 ID，两者不能混。
     *
     * @param event 带图片的入站事件
     */
    public void handleImages(OneBotEvent event) {
        Long groupId = event.getGroupId();
        String qq = String.valueOf(event.getUserId());

        Waiting pending = waiting.get(qq);
        if (pending == null) {
            // 没在等 —— 群里的图绝大多数是闲聊，静默丢弃
            return;
        }

        if (pending.at().plus(WAIT_WINDOW).isBefore(LocalDateTime.now(clock))) {
            waiting.remove(qq);
            client.sendGroupMessage(groupId,
                    "该截图超过 5 分钟的受理时限，已不再受理。如需补交付款截图，请重新发送 fw结账 指令。");
            return;
        }

        PaymentTargetHandler handler = handlerOf(pending.targetType());
        BizResult<PaymentTarget> loaded = handler == null
                ? null : handler.loadForProof(pending.targetId(), pending.userId());
        PaymentTarget target = loaded != null && loaded.isSuccess() ? loaded.getData() : null;
        if (target == null) {
            waiting.remove(qq);
            client.sendGroupMessage(groupId,
                    "未找到待付订单，付款截图未提交。请前往网页端查看订单列表。");
            return;
        }
        if (!target.isPendingPayment()) {
            // ⚠️ 这就是「走了网页途径就取消接受群内图片」：不做通知，只在这里回查一次状态。
            // 用户在网页端付过之后，这张图不该再提交一遍（会撞 uk_target，也没有意义）
            waiting.remove(qq);
            client.sendGroupMessage(groupId,
                    "该订单已不是待支付状态（可能已在网页端完成付款），本次截图未重复提交。");
            return;
        }

        List<OneBotImage> images = event.images();
        byte[] bytes = imageFetcher.fetch(images.get(0).url());
        if (bytes == null) {
            // 与「图太大」「地址取不到」等情形同一句：反正就是没收到，让他重发或走网页
            client.sendGroupMessage(groupId,
                    "截图获取失败（可能超出大小上限），请重新发送，或前往网页端上传。");
            return;
        }

        BizResult<ProofImageVo> stored = proofImageService.upload(bytes, pending.userId());
        if (!stored.isSuccess()) {
            client.sendGroupMessage(groupId,
                    "截图受理失败：" + stored.resolveMessage() + "。请前往网页端上传一张清晰的截图。");
            return;
        }

        // ⚠️ 来源传 QQ：这条路的流水上要看得出来是群里传的图，
        // 与网页端那条区分开（两处走的是同一个 submit）
        BizResult<ProofSubmitVo> submitted = paymentProofService.submit(
                pending.userId(), buildRequest(target, stored.getData()), TradeSource.QQ);
        // 无论提交成败，这次等待都结束了 —— 不然他会一直以为自己还欠一张图
        waiting.remove(qq);

        if (!submitted.isSuccess()) {
            client.sendGroupMessage(groupId,
                    "付款截图提交失败：" + submitted.resolveMessage() + "。请前往网页端重新上传。");
            return;
        }

        // isDelivered 一并报给用户：识别到有效单号的那两类当场就结清了，
        // 没识别到的还挂在待复核上 —— 两句话必须分开说，否则他会以为钱已经算数了
        client.sendGroupMessage(groupId, QqReplyText.proofAccepted(
                target.getOutTradeNo(), stored.getData().getOcrAmount(),
                stored.getData().getOcrPaymentNo(), submitted.getData().isDelivered()));
        log.info("[QQ机器人] 群内付款截图已受理 target={}#{} 单号={}",
                pending.targetType(), pending.targetId(), target.getOutTradeNo());
    }

    /**
     * 找到某一类收款的处理器。
     *
     * @param type 目标类型
     * @return 处理器；没有对应实现时返回 null（理论上不会发生 —— 类型来自本类自己登记的记录）
     */
    private PaymentTargetHandler handlerOf(PaymentTargetType type) {
        for (PaymentTargetHandler handler : handlers) {
            if (handler.type() == type) {
                return handler;
            }
        }
        log.error("[QQ机器人] 找不到收款处理器 type={} —— 群内传图这条链路会一直失败", type);
        return null;
    }

    /**
     * 把落盘与识别的结果拼成提交凭证的请求。
     *
     * <p>⚠️ <b>不传 {@code payQrId}</b>：那个字段记的是「这笔钱扫的是哪张收款码」，
     * 是给对账用的。群里发图这条路无从得知用户扫的是哪一张 —— 而瞎填一个
     * 比他留空更坏（对账时会把钱记到错的账号上）。
     *
     * @param target 待付的目标（决定 {@code targetType} 与 {@code targetId}）
     * @param vo     落盘 + 识别的结果
     * @return 提交请求
     */
    private static ProofSubmitRequest buildRequest(PaymentTarget target, ProofImageVo vo) {
        ProofSubmitRequest request = new ProofSubmitRequest();
        request.setTargetType(target.getType());
        request.setTargetId(target.getId());
        request.setProofUrl(vo.getProofUrl());
        // 识别结果原样带上 —— 它只是辅助线索，复核永远是人做的（见 OCR 那节的纪律）
        request.setOcrPaymentNo(vo.getOcrPaymentNo());
        request.setOcrAmount(vo.getOcrAmount());
        request.setOcrText(vo.getOcrText());
        return request;
    }

    /**
     * 一条「正在等图」的记录。
     *
     * <p>⚠️ {@code userId} 是 {@code sys_user.id}，<b>它不参与查表</b> ——
     * 查表的键是 QQ 号（见 {@code waiting} 字段）。这里留着它是因为
     * 入站事件给不出用户 ID，而载入目标（含归属校验）非要它不可。
     *
     * @param userId     用户 ID，载入目标时校验归属用它
     * @param targetType 这是哪一类收款
     * @param targetId   目标主键
     * @param at         登记时刻，用于判超时
     */
    private record Waiting(Long userId, PaymentTargetType targetType, Long targetId,
                           LocalDateTime at) {
    }
}
