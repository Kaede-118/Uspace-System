/**
 * QQ机器人（模块 11）。
 *
 * <p>对应毕业论文中的「QQ机器人」章节。
 *
 * <h3>定位：只读的信息播报与查询端</h3>
 *
 * <p><b>本包不承载任何业务写操作</b> —— 不开门、不结账、不下单、不改单。
 * 它只做两件事：
 * <ul>
 *   <li><b>查询</b> —— 群成员发指令（{@code /在店}），本包解析后调其他模块的
 *       只读 Service，把结果发回群</li>
 *   <li><b>播报</b> —— 业务侧（订单到店 / 离店）发布 Spring 事件，
 *       本包监听后组装文案推群</li>
 * </ul>
 *
 * <p>写操作全部挪走的理由见设计文档第十章（支付流水会对不上、资金动作不该由
 * 群消息触发）。一个由此而来的简化：<b>本包不写事务</b>，
 * 幂等也从「正确性问题」降级成了「体验问题」。
 *
 * <h3>依赖方向：单向，且不可反转</h3>
 *
 * <pre>
 * qqbot 包  →  可以调用  →  其他模块的 Service（只读）
 * qqbot 包  ←  禁止依赖  ←  其他模块
 * </pre>
 *
 * <p><b>播报不破坏这条规则</b>：业务侧只发布 Spring 事件，不 import 本包的任何类；
 * 本包用 {@code @TransactionalEventListener} 主动监听。编译期 {@code order}
 * 不认识 {@code qqbot}，运行时靠事件解耦。
 *
 * <p>⚠️ 由此推出一条容易写反的规矩：<b>事件类住在发布方所在的包</b>
 * （见 {@code com.kaede.uspace.order.event}），本包去 import 它。
 * 图省事把事件类放进本包的话，{@code order} 就得反过来 import {@code qqbot}，
 * 单向依赖当场就断了 —— 而这在编译期没有任何提示。
 *
 * <h3>本包不经过 HTTP 认证层</h3>
 *
 * <p>它的入口是一条反向 WebSocket，不是 HTTP 接口。来自群消息的身份是
 * <b>QQ 号</b>（NapCat 推来的 {@code user_id}），本包据此查 {@code sys_user}
 * 得到系统用户，再直接调 Service。<b>Service 层只接收 userId 参数、不感知 JWT</b>，
 * Web 端与 QQ bot 才能复用同一套业务逻辑。
 *
 * <h3>子包</h3>
 *
 * <ul>
 *   <li>{@code protocol/} —— OneBot v11 的协议映射对象（入站事件、出站动作与响应）。
 *       照 {@code order/ocr/} 的先例开子包：它们是<b>与外部协议对接</b>的那一层，
 *       换协议只动这里</li>
 * </ul>
 */
package com.kaede.uspace.qqbot;
