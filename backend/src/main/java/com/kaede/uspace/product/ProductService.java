package com.kaede.uspace.product;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.product.dto.CreateProductOrderRequest;
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductSaveRequest;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.product.entity.ProductOrder;
import com.kaede.uspace.product.event.ProductOrderCancelledEvent;
import com.kaede.uspace.product.mapper.ProductMapper;
import com.kaede.uspace.product.mapper.ProductOrderMapper;
import com.kaede.uspace.product.mapper.ProductPendingCount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品服务。
 *
 * <p>职责：实体商品的<b>目录与购买单</b> —— 陈列、详情、下单、取消、查单，
 * 以及后台的商品维护。
 *
 * <p><b>收款不在这里</b>：创建出来的购买单处于待支付状态，付款走模块 8 的
 * 统一支付入口（{@code POST /api/payments}，{@code targetType = PRODUCT}），
 * 支付成功后由 {@code order} 包的 {@code ProductPaymentTargetHandler}
 * 在同一个事务里走两跳：本表转已支付 + 扣减库存。
 * 这条边界与月卡、包场完全一致 —— 售卖归本模块，收款归模块 8。
 *
 * <p><b>库存扣减为什么不在下单时做</b>：见 {@link #createOrder} 上那一大段注释。
 */
@Slf4j
@Service
public class ProductService {

    /** 商品数据访问 */
    private final ProductMapper productMapper;

    /** 购买单数据访问 */
    private final ProductOrderMapper orderMapper;

    /** 商品配置：待支付单的存活时长 */
    private final ProductProperties properties;

    /**
     * 事件发布器：未付款的购买单被取消时发一条，由 {@code order} 包记进交易流水。
     *
     * <p>走事件而不是直接调用 {@code TradeLogService}：流水住在 {@code order} 包，
     * 直接引用会让本包反向依赖它，与「业务包依赖单向」那条规矩相抵
     * （见 {@link ProductOrderCancelledEvent} 的类注释）。
     */
    private final ApplicationEventPublisher events;

    public ProductService(ProductMapper productMapper,
                          ProductOrderMapper orderMapper,
                          ProductProperties properties,
                          ApplicationEventPublisher events) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.properties = properties;
        this.events = events;
    }

    // ==================================================================
    // 用户端
    // ==================================================================

    /**
     * 列出全部上架商品。
     *
     * <p>下架的商品不在这里 —— 那是「暂时不卖」，不该出现在顾客眼前。
     * 但它们的详情仍可打开，因为用户的订单里可能还指着它。
     *
     * @return 商品列表，按排序值与 ID 升序
     */
    public BizResult<List<ProductVo>> listOnSale() {
        List<Product> products = productMapper.selectOnSaleList();
        Map<Long, Integer> pending = pendingQuantitiesOf(
                products.stream().map(Product::getId).toList());

        return BizResult.ok(products.stream()
                .map(p -> ProductVo.from(p, pending.getOrDefault(p.getId(), 0)))
                .toList());
    }

    /**
     * 查一件商品的详情。
     *
     * <p><b>下架的商品也返回</b>（带上 {@code enabled = false}）：用户的订单列表里
     * 可能还指着它，点进去看不了会很莫名其妙。下架只影响「能不能下单」，
     * 不影响「能不能看」—— 商品信息本来就是对所有人公开的。
     *
     * @param id 商品 ID
     * @return 商品详情；不存在时返回 {@link ErrorCode#PRODUCT_NOT_FOUND}
     */
    public BizResult<ProductVo> detail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            return BizResult.fail(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return BizResult.ok(ProductVo.from(product, pendingQuantityOf(id)));
    }

    /**
     * 按名字查一件商品 —— <b>群里的下单指令走这条</b>（{@code fw可乐-2}）。
     *
     * <p>群里没有地方填商品 ID，顾客打出来的就是名字，所以名字必须能定位到唯一一件 ——
     * 这正是 {@code uk_name} 那条唯一键存在的理由。
     *
     * <p>返回的 {@code ProductVo} 与列表、详情<b>同一口径</b>
     * （可售量、售罄与否都算上了未支付的占用），所以群里报的「还剩几件」
     * 与网页上看到的必然一致。名字对不上时返回
     * {@link ErrorCode#PRODUCT_NOT_FOUND}，由调用方决定怎么说话。
     *
     * <p>⚠️ <b>不在这里判断「能不能下单」</b>：下架、售罄、库存不足各有各的错误码与
     * 文案，都由 {@link #createOrder} 一处给出。这里只负责「找到它」。
     *
     * @param name 商品名，去掉首尾空白后精确匹配
     * @return 成功时返回商品；没有这件商品时返回 {@link ErrorCode#PRODUCT_NOT_FOUND}
     */
    public BizResult<ProductVo> findLiveByName(String name) {
        Product product = productMapper.selectLiveByName(trimToNull(name));
        if (product == null) {
            return BizResult.fail(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return BizResult.ok(ProductVo.from(product, pendingQuantityOf(product.getId())));
    }

    /**
     * 下单买商品，落一条待支付购买单。
     *
     * <p>付款走模块 8 的统一支付入口（{@code POST /api/payments}，
     * {@code targetType = PRODUCT}，{@code targetId} 传本单 ID），
     * 支付成功后回调会扣减库存。
     *
     * <p><b>⚠️ 库存是「支付成功时才扣」，这不是最严格的方案。</b>
     * 严格方案是下单时预占、超时释放（月卡的购买单走的就是那条路，
     * 因为它「一人一卡」，被占用的是用户自己的购买资格）。
     * 支付时才扣的固有风险是：两人同时买最后一件，都能下单成功，
     * 后付款的那个会扣成负数 —— 钱收了却没货。
     *
     * <p><b>缓解办法是下面这次软检查</b>：可售量 = 库存 − 未支付的待支付单数，
     * 不够就拒绝下单。它<b>不锁库存</b>，所以理论上仍有竞态窗口，
     * 但把「已经有人在下单但还没付」这段最容易撞的时间差挡掉了 ——
     * 单店量级下这已经足够，且实现成本只有一条 SQL。
     *
     * <p><b>它同时也是「列表上是否售罄」的口径</b>（同一个
     * {@link Product#availableStock}），所以不会出现「页面说还有货、
     * 下单却说售罄」这种展示与判定打架的情况。
     *
     * <p><b>真正的最后一道防线在支付回调那一侧</b>：扣减用的是条件 UPDATE
     * （{@code WHERE stock >= ?}），受影响行数为 0 就说明确实卖光了，
     * 那一笔支付转入人工处理 —— 而不是在回调里抛异常，
     * 那会让支付平台不断重推一笔永远处理不了的通知。
     *
     * <p><b>另有一道「一笔没了结就不给下新的」</b>（2026-10-10 加）：名下有
     * {@code PENDING_PAYMENT} 或 {@code REJECTED} 的商品单时直接拒绝，
     * 两种状态给不同的错误码。它是「欠费拦截」在商品上的对应物，
     * 详细口径见方法体开头那段注释 —— 与「超时不占库存」是两回事。
     *
     * <p>本方法<b>不加事务</b>：这道软检查本来就不保证原子性，
     * 套上 {@code @Transactional} 只会让人误以为这里有更强的保证。
     *
     * @param userId  购买人
     * @param request 商品 ID 与数量
     * @return 成功时返回购买单（含单号与应付金额）；商品不存在、已下架或售罄时返回对应错误码
     */
    public BizResult<ProductOrderVo> createOrder(Long userId, CreateProductOrderRequest request) {
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            return BizResult.fail(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (product.getEnabled() == null || product.getEnabled() != 1) {
            return BizResult.fail(ErrorCode.PRODUCT_STATUS_INVALID, "该商品已下架");
        }

        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        // ⚠️ 数量范围必须在这里再挡一道：Bean Validation 的 @Min/@Max 只作用于
        // Controller 入参，而【群里的下单指令直接调本方法】—— 少了这一道，
        // fw可乐-0 会建出一笔 0 元的单、fw可乐--5 会算出一笔负金额的订单，
        // 而且两者都不报任何错
        if (quantity < 1 || quantity > CreateProductOrderRequest.MAX_QUANTITY) {
            return BizResult.fail(ErrorCode.PARAM_INVALID,
                    "单次最多买 " + CreateProductOrderRequest.MAX_QUANTITY + " 件、至少 1 件");
        }
        int available = product.availableStock(pendingQuantityOf(product.getId()));
        if (available < quantity) {
            return BizResult.fail(ErrorCode.PRODUCT_SOLD_OUT,
                    available <= 0 ? "该商品已售罄" : "该商品仅剩 " + available + " 件");
        }

        // 一笔没了结就不给下新的（2026-10-10 加）：查的是 PENDING_PAYMENT + REJECTED
        // 两种状态，对应两件处置方式不同的事，错误码与提示都分开给 ——
        //   · PENDING_PAYMENT 是「下单了没付钱」：群里连发几条 fw可乐-2
        //     就能挂起一串待支付单，每笔都占着库存，而货还在货架上没人动；
        //   · REJECTED 是「付过但凭证没通过」：他还欠着这笔钱，要先重传凭证。
        // 出口是现成的：付款，或取消（网页端「商品订单」页、群里的 fw取消）。
        //
        // ⚠️ 这道校验与「超时不占库存」的口径刻意不同：那个时间窗管的是
        // 「还占不占库存」，这里管的是「能不能再下单」—— 两天前没付的单子
        // 今天照样挡人，否则「欠着费接着买」正好从这道缝里漏过去。
        //
        // 位置与其他校验一致（放在创建之前、商品各项检查之后）：坏商品名、
        // 已下架、售罄这些先报 —— 它们说的是「这次请求本身有问题」，
        // 而欠费说的是「你的账号有笔账没了结」，后者在请求本身成立时才轮到说。
        ProductOrder unsettled = orderMapper.selectUnsettledByUser(userId);
        if (unsettled != null) {
            if (ProductOrderStatus.REJECTED.name().equals(unsettled.getStatus())) {
                return BizResult.fail(ErrorCode.PRODUCT_PROOF_REJECTED);
            }
            return BizResult.fail(ErrorCode.PRODUCT_UNPAID_EXISTS,
                    "你有一笔未付款的商品单（" + unsettled.getOrderNo()
                            + "），请先完成支付或取消该单");
        }

        ProductOrder order = new ProductOrder();
        order.setOrderNo(ProductNo.generate());
        order.setUserId(userId);
        order.setProductId(product.getId());
        // 名称与单价在这里快照，之后改名或调价都不影响这一单
        order.setProductName(product.getName());
        order.setUnitPrice(product.getPrice());
        order.setQuantity(quantity);
        order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(ProductOrderStatus.PENDING_PAYMENT.name());
        orderMapper.insert(order);

        log.info("[商品] 用户 {} 下单 {} ×{}，单号 {} 金额 {} 元",
                userId, product.getName(), quantity, order.getOrderNo(), order.getAmount());
        return BizResult.ok(ProductOrderVo.from(order));
    }

    /**
     * 取消一笔待支付的购买。
     *
     * <p><b>这不是退货</b> —— 那笔钱从来没付过，库存也从来没扣过。
     * 取消只是把单子关掉，让它不再占着可售量。
     *
     * <p>非本人的订单一律按「不存在」处理，不用 403：403 等于承认
     * 「这个单子存在，只是不归你」，可以被用来枚举单号。
     *
     * <p>成功之后发一条 {@link ProductOrderCancelledEvent} —— 由
     * {@code order} 包的 {@code TradeLogListener} 记进交易流水。
     * 钱一分没动，但「那笔挂着的单后来怎么了」值得留档。
     *
     * <p><b>本方法加 {@code @Transactional}</b>：取消本身只是一条 UPDATE，
     * 事务是为<b>流水那一行与它同生共死</b>而加的（监听器是同步的、跑在同一个
     * 事务里）。少了这层事务，会出现「取消成功、流水插入失败」——
     * 那笔单从账上消失得无影无踪，而两边都不报错。
     *
     * @param userId  当前登录用户
     * @param orderId 购买单 ID
     * @param source  来源渠道（网页端 / 群内），只进流水，不参与任何判断
     * @return 成功返回空数据；单子不存在、不归本人、或已不是待支付状态时返回对应错误码
     */
    @Transactional
    public BizResult<Void> cancelOrder(Long userId, Long orderId, TradeSource source) {
        ProductOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.PRODUCT_ORDER_NOT_FOUND);
        }
        if (orderMapper.closePending(orderId) == 0) {
            return BizResult.fail(ErrorCode.PRODUCT_STATUS_INVALID, "该购买单不是待支付状态，无法取消");
        }
        log.info("[商品] 用户 {} 取消了商品购买单 {}", userId, order.getOrderNo());
        events.publishEvent(new ProductOrderCancelledEvent(
                order.getOrderNo(), userId, order.getAmount(), source));
        return BizResult.ok(null);
    }

    /**
     * 按<b>单号</b>取消未付款的购买单 —— 群里的 {@code fw取消 <单号>} 走这条。
     *
     * <p>网页端的取消接口按 ID 走（REST 语义），而群里没有地方填 ID，
     * 用户手里只有单号，所以另开一个入口。两条最终汇进 {@link #cancelOrder}，
     * 守卫（本人 + 待支付）与流水只有一份。
     *
     * <p><b>本方法自己带 {@code @Transactional}</b>：内部调 {@link #cancelOrder}
     * 属于同类自调用，那个方法上的事务注解不会生效 —— 少了这一层，
     * 「取消成功、流水插入失败」会分成两个独立事务，那笔单从账上消失得
     * 无影无踪，而两边都不报错。
     *
     * @param userId  当前用户（必须是这张单的主人）
     * @param orderNo 购买单号
     * @param source  来源渠道（网页端 / 群内），只进流水
     * @return 成功返回空数据；单号不存在、不归本人、或已不是待支付状态时返回对应错误码
     */
    @Transactional
    public BizResult<Void> cancelOrderByNo(Long userId, String orderNo, TradeSource source) {
        ProductOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.PRODUCT_ORDER_NOT_FOUND);
        }
        return cancelOrder(userId, order.getId(), source);
    }

    /**
     * 查我的商品订单，分页。
     *
     * @param userId   当前登录用户
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param status   状态筛选，可空
     * @return 分页结果，最近的在前
     */
    public BizResult<PageResult<ProductOrderVo>> myOrders(Long userId, long pageNum,
                                                          long pageSize, String status) {
        return listOrders(pageNum, pageSize, userId, status);
    }

    /**
     * 列出某人全部未付款的购买单 —— 群里的 {@code fw未付款} 用。
     *
     * @param userId 用户 ID
     * @return 未付款的购买单，最近的在前；没有时返回空列表
     */
    public BizResult<List<ProductOrderVo>> listUnpaidOrders(Long userId) {
        return BizResult.ok(orderMapper.selectUnpaidByUser(userId).stream()
                .map(ProductOrderVo::from)
                .toList());
    }

    // ==================================================================
    // 运营后台
    // ==================================================================

    /**
     * 分页查询商品，含已下架的。
     *
     * <p>可售量与用户端用同一口径（都算上未支付的占用），
     * 免得后台看到的「还剩 5 件」与顾客看到的对不上。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword  名称关键词，可空
     * @param enabled  上架状态筛选（1/0），可空
     * @param stockAsc 是否按「库存从少到多」排（补货优先）。
     *                 <b>排序做在 SQL 里</b>，理由见 {@code ProductMapper#selectPageBy}
     * @return 分页结果
     */
    public BizResult<PageResult<ProductVo>> listAll(long pageNum, long pageSize,
                                                    String keyword, Integer enabled,
                                                    boolean stockAsc) {
        IPage<Product> page = productMapper.selectPageBy(
                new Page<>(pageNum, pageSize), trimToNull(keyword), enabled, stockAsc);

        Map<Long, Integer> pending = pendingQuantitiesOf(
                page.getRecords().stream().map(Product::getId).toList());
        return BizResult.ok(PageResult.of(page,
                p -> ProductVo.from(p, pending.getOrDefault(p.getId(), 0))));
    }

    /**
     * 新增一件商品。
     *
     * @param request 商品内容
     * @return 新建的商品
     */
    public BizResult<ProductVo> create(ProductSaveRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        // 名字查重。数据库上那条 uk_name 才是最终防线（并发下只靠这里挡不住），
        // 但先查一次能把「撞键」变成一句人话 —— 否则用户看到的是一句 500
        if (productMapper.countByName(product.getName(), null) > 0) {
            return BizResult.fail(ErrorCode.PRODUCT_NAME_EXISTS);
        }
        productMapper.insert(product);

        log.info("[商品] 新增商品 id={} 名称={} 价格={} 库存={}",
                product.getId(), product.getName(), product.getPrice(), product.getStock());
        return BizResult.ok(ProductVo.from(product, 0));
    }

    /**
     * 修改一件商品。
     *
     * <p><b>全量替换</b>：没传的字段按「清空 / 默认值」处理，
     * 唯一的例外是 {@code enabled}（见 {@code ProductSaveRequest} 上的说明）。
     *
     * <p>调价<b>不影响已售出的订单</b> —— 那些单子上存的是下单时的单价快照。
     *
     * @param id      商品 ID
     * @param request 商品内容
     * @return 修改后的商品；商品不存在时返回 {@link ErrorCode#PRODUCT_NOT_FOUND}
     */
    public BizResult<ProductVo> update(Long id, ProductSaveRequest request) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            return BizResult.fail(ErrorCode.PRODUCT_NOT_FOUND);
        }
        applyRequest(product, request);
        // 改名同样要查重，且要排开自己（{@code excludeId}）—— 不改名只改价格时，
        // 不排开自己的话每一次保存都会被自己挡住
        if (productMapper.countByName(product.getName(), id) > 0) {
            return BizResult.fail(ErrorCode.PRODUCT_NAME_EXISTS);
        }
        // 走显式 SQL 而不是 updateById：后者的默认字段策略会跳过 null 字段，
        // 而「没传就是清空」正是 PUT 的语义。少了这一步，撤封面、清描述都做不到，
        // 且不会报任何错（接口 200，刷新后旧值还在）
        productMapper.updateProduct(product);

        log.info("[商品] 修改商品 id={} 名称={} 价格={} 库存={} 上架={}",
                id, product.getName(), product.getPrice(), product.getStock(), product.getEnabled());
        return BizResult.ok(ProductVo.from(product, pendingQuantityOf(id)));
    }

    /**
     * 把库存<b>设成给定的值</b> —— 后台的「只改库存」与群里的库存指令走这一条。
     *
     * <p>与 {@link #update} 的区别是「改多少东西」：那一条是全量替换，得把名称、
     * 封面、描述、价格一并带上；这一条只动 {@code stock} 一列，其余字段一个都不碰。
     * 拿全量替换来做补货的话，调用方必须先读整条记录、改完再传回，中间若有别人
     * 改了名称就会被静默覆盖 —— 与模块 4「机台单独改状况」是同一个取舍。
     *
     * <p>⚠️ <b>数量在这里再挡一道</b>：Bean Validation 的 {@code @Min(0)} 只作用于
     * Controller 入参，而<b>群里的指令是直接调本方法的</b> —— 少了这一道，
     * 负数会把库存写成负值，此后可售量与「是否售罄」的判断全部失真。
     * （与 {@link #createOrder} 里挡数量范围是同一条理由。）
     *
     * @param id    商品 ID
     * @param stock 新的库存值
     * @return 调整后的商品（含可售量）；商品不存在时返回
     *         {@link ErrorCode#PRODUCT_NOT_FOUND}
     */
    public BizResult<ProductVo> updateStock(Long id, int stock) {
        if (stock < 0) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "库存不能为负数");
        }
        Product product = productMapper.selectById(id);
        if (product == null) {
            return BizResult.fail(ErrorCode.PRODUCT_NOT_FOUND);
        }
        int before = product.getStock() == null ? 0 : product.getStock();
        // 影响行数为 0 = 查出来之后被人删掉了（并发）。不判的话这里会回一句
        // 「已调整」而库里什么都没变 —— 与「报告成功但没做」相比，多这一次判断便宜得多。
        // ⚠️ 这里依赖驱动默认的「返回匹配行数」语义：把库存改成本来就相同的值时仍算
        // 1 行。若将来给 JDBC URL 加上 useAffectedRows=true（返回实际改变的行数），
        // 同值更新会返回 0 —— 那时这一句就会把「改成同一个数」误报成「商品不存在」
        if (productMapper.updateStock(id, stock) == 0) {
            return BizResult.fail(ErrorCode.PRODUCT_NOT_FOUND);
        }
        product.setStock(stock);
        log.info("[商品] 调整库存 id={} 名称={} {} → {}", id, product.getName(), before, stock);
        return BizResult.ok(ProductVo.from(product, pendingQuantityOf(id)));
    }

    /**
     * 下架并删除一件商品（逻辑删除）。
     *
     * <p>删掉之后它从用户端与后台列表双双消失，但<b>历史订单不受影响</b> ——
     * 那些单子上存的是下单时的名称与价格快照，不依赖商品记录还在不在。
     *
     * <p>只是想「暂时不卖」的话用 {@code PUT} 把 {@code enabled} 置 0，
     * 那样还能重新上架。
     *
     * @param id 商品 ID
     * @return 成功返回空数据；商品不存在时返回 {@link ErrorCode#PRODUCT_NOT_FOUND}
     */
    public BizResult<Void> delete(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            return BizResult.fail(ErrorCode.PRODUCT_NOT_FOUND);
        }
        productMapper.deleteById(id);
        log.info("[商品] 删除商品 id={} 名称={}", id, product.getName());
        return BizResult.ok(null);
    }

    /**
     * 分页查询商品订单，供运营后台使用。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param userId   购买人筛选，可空
     * @param status   状态筛选，可空
     * @return 分页结果
     */
    public BizResult<PageResult<ProductOrderVo>> listOrders(long pageNum, long pageSize,
                                                            Long userId, String status) {
        String statusFilter = trimToNull(status);
        if (statusFilter != null && !ProductOrderStatus.isValid(statusFilter)) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "商品订单状态取值不合法");
        }
        IPage<ProductOrder> page = orderMapper.selectPageBy(
                new Page<>(pageNum, pageSize), userId, statusFilter);
        return BizResult.ok(PageResult.of(page, ProductOrderVo::from));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 查一件商品被未支付的待支付单占掉多少件。
     *
     * @param productId 商品 ID
     * @return 占用量（件），没有则为 0
     */
    private int pendingQuantityOf(Long productId) {
        return pendingQuantitiesOf(List.of(productId)).getOrDefault(productId, 0);
    }

    /**
     * 批量查一批商品各自被未支付的待支付单占掉多少件。
     *
     * <p><b>批量查是为了一次列表只发一条 SQL</b>：逐件查是 N+1，
     * 商品列表虽短，但没有理由让它随商品数量线性退化。
     *
     * <p><b>口径是件数不是笔数</b>：一笔「买 5 件」的待支付单占掉 5 件库存。
     * 那条 SQL 用的是 {@code SUM(quantity)}，写成 {@code COUNT(*)} 不会报错，
     * 只会让可售量比实际多出一大截。
     *
     * <p>超时的待支付单不计入 —— 否则用户下单后不付款就把商品永久占住了
     * （见 {@code ProductProperties#pendingTimeout} 上的说明）。
     *
     * @param productIds 商品 ID 列表，可为空
     * @return 商品 ID 到占用件数的映射；没有占用的商品不在其中
     */
    private Map<Long, Integer> pendingQuantitiesOf(List<Long> productIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (productIds.isEmpty()) {
            // 空集合会让 SQL 拼出 IN ()，那是语法错误。列表为空时直接跳过
            return result;
        }
        LocalDateTime since = LocalDateTime.now().minus(properties.getPendingTimeout());
        for (ProductPendingCount row : orderMapper.countPendingByProduct(productIds, since)) {
            result.put(row.getProductId(), row.getPendingQuantity());
        }
        return result;
    }

    /**
     * 把请求里的内容写进实体。
     *
     * <p><b>{@code enabled} 的处理是这里唯一需要解释的一处</b>：
     * 传了就用传的，没传时 —— 新建的商品默认为上架，已存在的商品保持原值。
     * 这是全量替换（PUT）语义的唯一例外，理由写在
     * {@code ProductSaveRequest#enabled} 上：改个名字顺手把商品下架了
     * 而管理员毫无察觉，比「多写一个字段」严重得多。
     *
     * @param product 目标实体（新建的或从库里读出来的）
     * @param request 请求内容
     */
    private static void applyRequest(Product product, ProductSaveRequest request) {
        product.setName(request.getName().trim());
        product.setCover(trimToNull(request.getCover()));
        product.setDescription(trimToNull(request.getDescription()));
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());

        if (request.getEnabled() != null) {
            product.setEnabled(request.getEnabled() == 1 ? 1 : 0);
        } else if (product.getEnabled() == null) {
            product.setEnabled(1);
        }

        product.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
    }

    /**
     * 去首尾空白，空串归一为 null。
     *
     * <p>避免把 {@code ""} 写进库 —— 它在 SQL 的 {@code IS NULL} 判断里
     * 走的是另一条分支，会让「没填」与「填空串」产生不同的查询结果。
     *
     * @param value 原值
     * @return 去空白后的值；原值为 null 或全空白时返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
