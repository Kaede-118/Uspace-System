-- ============================================================================
-- 无人值守共享娱乐空间管理系统（uspace） 数据库建表脚本
--
-- 目标库：MySQL 8.0
-- 字符集：utf8mb4 / utf8mb4_general_ci（支持 emoji 与生僻字）
--
-- 使用方式：
--   mysql -u root -p uspace < schema.sql
--   或在客户端中打开本文件执行
--
-- ⚠️ 本脚本含 DROP TABLE IF EXISTS，可反复执行以重建表结构，
--    **但会清空表中数据**。仅用于开发与演示环境；生产环境上线后
--    请改用增量迁移脚本，不要重复执行本文件。
--
-- 设计约定（详见 docs/开发约定与设计说明.md）：
--   1. 金额一律 DECIMAL(10,2)，禁止 FLOAT / DOUBLE
--   2. 时间字段存本地时间（Asia/Shanghai）。计费规则按本地时钟定义
--      （日场 10:00–22:00），存 UTC 反而多一层换算与出错机会
--   3. 主要业务表带 deleted 逻辑删除标记，订单等财务数据不允许物理删除
--   4. 表名加前缀规避保留字：ORDER、LOCK 均为 MySQL 保留字，
--      不加前缀则每处 SQL 都要写反引号，第三方工具也易踩坑
--   5. 缺少外键约束是刻意的：便于分库分表与数据清理，关联完整性由应用层保证
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


-- ----------------------------------------------------------------------------
-- 清理已废弃的表
--
-- biz_space（旧「房间」表，2026-09-28 重构为 biz_store「门店」）
--   本脚本已不再创建它，但**老环境里它还在** —— 跑本脚本时需要一并清掉，
--   否则会残留一张空表，看 DESC 时容易被误认为是有效结构。
--   重构理由见下方「模块 3：门店管理」的表注释。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `biz_space`;


-- ============================================================================
-- 模块 1：用户管理
-- ============================================================================
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)   NOT NULL                COMMENT '登录名',
  `password_hash` VARCHAR(100)  NOT NULL                COMMENT '密码哈希（BCrypt），绝不存明文',
  `nickname`      VARCHAR(50)   DEFAULT NULL            COMMENT '昵称',
  `phone`         VARCHAR(20)   DEFAULT NULL            COMMENT '手机号',
  `qq`            VARCHAR(20)   DEFAULT NULL            COMMENT 'QQ 号，供机器人模块匹配身份',

  -- 游玩偏好：逗号分隔的设备类型 code，多选，可为空（没选也是一种合法状态）
  --   例 'PAIPAI,TAISHOU' 表示两种都玩；NULL 或空串表示未设置
  --   取值来自 biz_equipment_type.code（模块 4 的字典表），用户在网页端随时可改
  --   ⚠️ 刻意用逗号串而不是关联表：取值只有几个到十几个，且没有「按偏好精确筛选用户」
  --      这类需要索引的查询。真要用 SQL 筛时用 FIND_IN_SET（不走索引，但本店量级无所谓）
  `preference`    VARCHAR(64)   DEFAULT NULL            COMMENT '游玩偏好，逗号分隔的设备类型 code，可多选可为空。如 PAIPAI,TAISHOU',

  -- 头像与背景图：存【站内相对路径】而不是完整 URL ——
  --   完整 URL 会把域名写进库，换域名要 UPDATE ... REPLACE(...) 全表刷一遍。
  --   前端 <img :src="avatar"> 直接加载；为空时前端回落到默认头像 / 纯色背景。
  -- ⚠️ 这两列【不参与】PUT /api/user/me 的全量替换 —— 那个接口传 null 表示清空，
  --    混进去会让「只改昵称」的表单顺手把头像清掉，而且不报任何错。
  --    改它们走独立的上传接口 POST /api/user/me/avatar 与 /me/banner
  `avatar`        VARCHAR(255)  DEFAULT NULL            COMMENT '头像地址（站内相对路径），如 /uploads/avatar/xiaofeng_12.png。NULL 表示用默认头像',
  `banner`        VARCHAR(255)  DEFAULT NULL            COMMENT '自定义背景图地址（站内相对路径），约 6:1 横长图，用作个人卡片背景。NULL 表示用纯色兜底',

  `role`          VARCHAR(20)   NOT NULL DEFAULT 'USER' COMMENT '角色：USER 普通用户 / ADMIN 管理员',
  `status`        TINYINT       NOT NULL DEFAULT 1      COMMENT '状态：1=正常 0=禁用',
  `token_version` INT           NOT NULL DEFAULT 0      COMMENT 'JWT 版本号：封禁/改密时 +1，使该用户所有已签发的 token 立即失效',

  -- 累计消费：三列同源，只在「订单支付成功」与「月卡支付成功」两处累加，只增不减。
  -- ⚠️ 这三列【不计入】月度累计消费的优惠门槛 —— 后者由 biz_order 按月聚合、不含月卡充值。
  --    两者用途与口径都不同，详见 docs/开发约定与设计说明.md「两个累计消费口径」
  `order_paid`    DECIMAL(12,2) NOT NULL DEFAULT 0.00  COMMENT '累计订单实付（仅房间消费，终生累计）',
  `card_paid`     DECIMAL(12,2) NOT NULL DEFAULT 0.00  COMMENT '累计月卡充值实付',
  `total_paid`    DECIMAL(12,2) NOT NULL DEFAULT 0.00  COMMENT '累计实付总额 = order_paid + card_paid。冗余列，供前端展示与老客回馈筛选',

  `created_at`    DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`       TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  -- QQ 号唯一：模块 11 靠 QQ 号把群消息的发送者对应到系统用户，
  -- 重号会让机器人查到两个人、播报与查询结果都不确定。
  -- 应用层也会查重，但并发注册时挡不住，唯一索引是最终防线
  UNIQUE KEY `uk_qq` (`qq`),
  KEY `idx_total_paid` (`total_paid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户';

-- 初始管理员账号。
--
-- 为什么需要它：注册接口一律创建普通用户（USER），而运营后台的接口全部要求
-- ADMIN 角色。没有这条初始数据，全新部署的系统里没有任何人能登录后台。
--
-- ⚠️ 演示用，**生产环境部署后必须立即修改密码**。
--    这里的哈希对应密码 admin123（cost=10）。之所以敢把哈希明文写在脚本里，
--    是因为 BCrypt 的哈希自带随机盐、且算法本身是单向的，贴出来不泄露原文；
--    但它确实对应一个众所周知的弱口令，上线前务必改掉。
--    改法：登录后走「修改密码」接口，或直接 UPDATE 这一行的 password_hash。
--
-- 注意显式给出 created_at / updated_at —— 这两列 NOT NULL 且无默认值
-- （业务代码里由 MyBatis-Plus 自动填充，但脚本得自己带 NOW()）
INSERT INTO `sys_user` (`username`, `password_hash`, `nickname`, `role`, `status`, `token_version`,
                        `order_paid`, `card_paid`, `total_paid`,
                        `created_at`, `updated_at`, `deleted`)
VALUES ('admin', '$2a$10$mszXJruta1OmNFw5aJ2HIujmV73pgVIZVhbAzV92M4.f/TVJYJsZq', '系统管理员',
        'ADMIN', 1, 0,
        0.00, 0.00, 0.00,
        NOW(), NOW(), 0);


-- ============================================================================
-- 模块 3：门店管理
--
-- 为什么是「门店」而不是「房间」：
--   本系统的空间是【共享】的 —— 平时多组顾客同时在店内各玩各的，不存在
--   「一间房被一组人占用、别人进不来」的语义。原先按「房间」建模
--   （房型、容纳人数、FREE/IN_USE/MAINTENANCE 状态），在单空间下所有订单都
--   指向同一条记录，这些字段一个都约束不了任何东西。
--
--   而真实的扩张路径是【开分店】：一个品牌下多个门店，每个门店就是一个共享
--   娱乐空间。所以这一层实体的正确名字是「门店」。
--
-- 本表【不含】任何时段性的状态位：
--   「今天维护不对外营业」与「某时段被包场」都是【时间维度】的准入规则，
--   不是门店的固有属性 —— 压成一个 status 字段就表达不了「明天 10:00–14:00
--   维护」这种提前安排。两者各自成表：biz_closure（停业）、biz_booking（包场）。
--
--   同理，这里没有「空闲 / 使用中」—— 白天店里 3 组人、晚上 1 组人，
--   门店本身始终是这个门店，没有可切换的状态。
-- ============================================================================
DROP TABLE IF EXISTS `biz_store`;
CREATE TABLE `biz_store` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(50)  NOT NULL                COMMENT '门店名称，对外展示',
  `address`     VARCHAR(255) DEFAULT NULL            COMMENT '门店地址，供用户端展示与导航',
  `description` VARCHAR(255) DEFAULT NULL            COMMENT '门店说明，如「6 台音游机，可同时容纳 12 人」',
  `created_at`  DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '门店（共享娱乐空间）';

-- 初始门店记录：单门店运营，开箱即用。
--
-- 为什么必须有这条：下单、开门、订单归属都要引用门店 ID，
-- 表为空时整条主链路都跑不起来（接口会返回「门店不存在」）。
-- 名称与地址这里只是占位，管理员在后台自行修改即可。
--
-- 与初始管理员账号同理，注意显式给出 created_at / updated_at ——
-- 这两列 NOT NULL 且无默认值（业务代码里由 MyBatis-Plus 自动填充，
-- 建表脚本的 INSERT 得自己带 NOW()）。
INSERT INTO `biz_store` (`name`, `address`, `description`, `created_at`, `updated_at`, `deleted`)
VALUES ('共享娱乐空间', NULL, NULL, NOW(), NOW(), 0);


-- ============================================================================
-- 模块 3：停业记录
--
-- 「今天维护不对外营业」是时间维度的事，不是门店的状态。
-- 本表记录停业区间与原因，区间内一律拒绝新下单与开门。
--
-- 典型用法：管理员提前排「明天 10:00–14:00 设备维护」，到点自动拒绝新订单，
-- 无需人工盯守，事后也能查到当时为什么停业、是谁登记的。
--
-- 时段是【半开区间】[start_at, end_at)：10:00 起不营业，14:00 起恢复。
-- 这样「10:00–12:00」与「12:00–14:00」两段能自然相邻而不算重叠。
--
-- 已在店内的顾客不受影响 —— 停业挡的是「新的人进来」，不是「赶人走」。
-- 封闭空间的出门本来就是机械推杠/按钮（消防要求内部免密自由开门），
-- 系统既控制不了也没必要控制。
-- ============================================================================
DROP TABLE IF EXISTS `biz_closure`;
CREATE TABLE `biz_closure` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `store_id`   BIGINT       NOT NULL                COMMENT '门店 ID',
  `start_at`   DATETIME     NOT NULL                COMMENT '停业开始时刻（含），此刻起不对外营业',
  `end_at`     DATETIME     NOT NULL                COMMENT '停业结束时刻（不含），此刻起恢复营业',
  `reason`     VARCHAR(255) DEFAULT NULL            COMMENT '停业原因，如「设备维护」「春节休假」',
  `created_by` BIGINT       DEFAULT NULL            COMMENT '登记人（管理员 ID）',
  `created_at` DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  KEY `idx_store_range` (`store_id`, `start_at`, `end_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '停业记录';


-- ============================================================================
-- 模块 3：包场
--
-- 业务模式：默认共享 —— 谁都能下单进店，多组顾客同时在店内各玩各的。
--          管理员可为某个时段安排「包场」，该时段内只有包场人与被邀请者能进。
--
-- 流程：
--   ① 管理员在后台选时段、指定包场人、定价（生成一条 PENDING_PAYMENT 记录）
--   ② 包场人付款 → 状态转 PAID，生成 invite_token
--   ③ 包场人把邀请链接分享给朋友
--   ④ 被邀请者凭链接鉴权，与包场人一样可以「点击开门获取密码」
--   ⑤ 时段结束，包场结束；若仍在店则转普通计时计费
--
-- 准入控制靠「不发密码」实现，不靠门锁的禁止名单：
--   非被邀请者在该时段内拿不到有效密码，自然进不去。
--
--   门锁上【不设常驻有效密码】——「点击开门」只是把密码展示给已鉴权的人，
--   门必须有物理在场的动作（在门口输入密码）才会开。这是刻意的：
--   防止线上误点一下就把陌生人放进店。因此本系统【不提供远程直接开锁】。
--
-- 包场时段内不计费（包场费已预付），时段结束后仍逗留的部分按普通规则计时。
--
-- 订单与本表的挂接（模块 8 已细化）：biz_order.booking_id 记下「进店时命中的包场」，
--   结算时把包场时段从计费区间里剪掉。三个来源都要认 ——
--   ① 被邀请者：下单时正处包场时段，booking_id 指向该场；
--   ② 包场人提前到店：下单时包场尚未开始、booking_id 为空，
--      结算时按 host_user_id 回查，否则他会被重复计费（既付了包场费又付了计时费）；
--   ③ 参与者表命中（biz_booking_participant）：比准入窗口更早到店的被邀请者，
--      下单那一刻包场还没进窗口，订单同样不挂 booking_id —— 这条是后补的缺口。
-- ============================================================================
DROP TABLE IF EXISTS `biz_booking`;
CREATE TABLE `biz_booking` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `booking_no`     VARCHAR(32)   NOT NULL                COMMENT '包场单号，对外展示；支付时作商户订单号',
  `store_id`       BIGINT        NOT NULL                COMMENT '门店 ID',
  `host_user_id`   BIGINT        NOT NULL                COMMENT '包场人用户 ID',
  `start_at`       DATETIME      NOT NULL                COMMENT '包场开始时刻（含）',
  `end_at`         DATETIME      NOT NULL                COMMENT '包场结束时刻（不含）',
  `price`          DECIMAL(10,2) NOT NULL                COMMENT '包场价格（元），一口价预付，不按分钟计',
  `status`         VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT'
                   COMMENT '状态：PENDING_PAYMENT 待付款 / PAID 已付款（准入生效）/ CANCELLED 已取消 / CLOSED 已结束',
  `payment_method` VARCHAR(20)   DEFAULT NULL            COMMENT '支付通道，取值同 biz_order.payment_method',
  `payment_no`     VARCHAR(64)   DEFAULT NULL            COMMENT '支付平台交易号',
  `paid_at`        DATETIME      DEFAULT NULL            COMMENT '支付完成时刻，也是邀请链接开始可用的时刻',
  `invite_token`   VARCHAR(64)   DEFAULT NULL            COMMENT '邀请令牌，付款后生成，被邀请者凭它鉴权',
  `remark`         VARCHAR(255)  DEFAULT NULL            COMMENT '备注',
  `created_by`     BIGINT        DEFAULT NULL            COMMENT '安排人（管理员 ID）',
  `created_at`     DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_no` (`booking_no`),
  UNIQUE KEY `uk_invite_token` (`invite_token`),
  KEY `idx_store_range` (`store_id`, `start_at`, `end_at`),
  KEY `idx_host_user` (`host_user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '包场';


-- ============================================================================
-- 模块 3：空间管理 —— 包场参与者
--
-- 「谁在这场包场里」的权威来源：包场人与被邀请者都记在这张表，靠 role 区分。
-- 下单准入、包场开始的清场、结算时的包场时段剪切，三处都读它。
--
-- 为什么用关联表而不是 biz_booking.participants 逗号串（sys_user.preference 是先例）：
--   ① 「我参与的包场」正是【按人筛选】，需要索引；而 preference 那条先例的前提
--      恰恰写在它的列注释里 ——「取值只有几个到十几个，且没有按偏好精确筛选用户的查询」；
--   ② 去重语义由数据库保证 —— 重复点邀请链接是撞 uk_booking_user 唯一键。
--      换成逗号串就得靠一句写对的原子 UPDATE，两人同时点链接会静默丢掉一个，
--      被丢的那个自己也不知道。
--
-- HOST 行在包场付款成功的那一刻写入（与邀请令牌同一处、同一事务）——
-- 那是包场从「安排」变成「事实」的唯一时刻。因此【待付款的包场没有 HOST 行】，
-- 「我创建的包场」列表仍按 biz_booking.host_user_id 查，不能改读本表。
-- ============================================================================
DROP TABLE IF EXISTS `biz_booking_participant`;
CREATE TABLE `biz_booking_participant` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `booking_id` BIGINT      NOT NULL                COMMENT '包场 ID',
  `user_id`    BIGINT      NOT NULL                COMMENT '参与人用户 ID',
  `role`       VARCHAR(20) NOT NULL                COMMENT '角色：HOST 包场人（创建者）/ PARTICIPANT 被邀请者。见 BookingParticipantRole',
  `joined_at`  DATETIME    NOT NULL                COMMENT '加入时刻。HOST 行 = 包场付款时刻；PARTICIPANT 行 = 点邀请链接的时刻',
  `created_at` DATETIME    NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME    NOT NULL                COMMENT '更新时间',
  `deleted`    TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_user` (`booking_id`, `user_id`),
  KEY `idx_user_role` (`user_id`, `role`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '包场参与者';


-- ============================================================================
-- 模块 4：设备管理 —— 设备类型字典
--
-- 本表是「机器类型」的字典（拍拍机、抬手乐…）；
-- biz_device（具体某台机器，待建）每台指向本表的一个类型。
--
-- 为什么单独建字典表而不是把类型写死在代码里：
--   场馆的机器类型会随经营调整（进新机、淘汰旧机），做成字典后
--   由运营在后台自行增删，不必改代码重新部署。
--   而且「用户喜欢玩什么」与「这台机器是什么类型」共用这一份取值，
--   避免两边各维护一套、慢慢漂移。
--
-- ⚠️ 用户的偏好存在 sys_user.preference，是逗号分隔的 code 串，不是关联表 ——
--    理由见 sys_user 中该列的注释。
-- ============================================================================
DROP TABLE IF EXISTS `biz_equipment_type`;
CREATE TABLE `biz_equipment_type` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`       VARCHAR(32)  NOT NULL                COMMENT '类型代码，如 PAIPAI / TAISHOU。存进 sys_user.preference 的就是它',
  `name`       VARCHAR(50)  NOT NULL                COMMENT '类型名称，如「拍拍机」「抬手乐」',
  `sort`       INT          NOT NULL DEFAULT 0      COMMENT '排序权重，越小越靠前。决定前端复选框与列表的显示顺序',
  `enabled`    TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用：1=启用 0=停用。停用后不再出现在可选项里，但历史偏好仍能解析出名称',
  `remark`     VARCHAR(255) DEFAULT NULL            COMMENT '备注',
  `created_at` DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_enabled_sort` (`enabled`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '设备类型字典';

-- 初始数据：当前场馆已知的两类机器。后续由运营在后台增删
-- 注意显式给出 created_at / updated_at —— 这两列是 NOT NULL 且无默认值
-- （业务表里它们由 MyBatis-Plus 自动填充，但建表脚本的 INSERT 得自己带上）
INSERT INTO `biz_equipment_type` (`code`, `name`, `sort`, `created_at`, `updated_at`) VALUES
  ('PAIPAI',  '拍拍机', 10, NOW(), NOW()),
  ('TAISHOU', '抬手乐', 20, NOW(), NOW());


-- ============================================================================
-- 模块 4：设备管理 —— 机台台账
--
-- 本表回答的是「店里有哪些机器、每台什么状况」，即一份【资产台账】，
-- 每台指向 biz_equipment_type 的一个类型。
--
-- ⚠️ 本表【不进主链路】：不参与计费，也不绑定订单。
--    系统是共享模式，用户下单时既不选机台，计费也不看他玩了哪台 ——
--    所以这里的 status 是「这台还能不能玩」，【不是】「谁正占着」。
--    要做到「谁在玩哪台」需要设备级使用记录，当前明确不做，理由见
--    docs/开发约定与设计说明.md 的「待定事项」。
--
-- ⚠️ 机台状况【不影响准入】：能不能进店只由停业（biz_closure）与包场
--    （biz_booking）决定。哪怕全店机器都标成「维护中」，系统照样放人进门 ——
--    真想拦住要排一条停业区间，而不是改机台状态。
--
-- ⚠️ 「维护中」的机台【照样陈列】、不隐藏：陈列的目的就是让顾客看到
--    「这台在修」，隐藏反而会让人以为机器搬走了。
--
-- uk_store_device_no 里的 device_no 允许为空，且 MySQL 的唯一索引
-- 【允许多行为 NULL】（NULL 不等于 NULL）—— 所以「几台机器都还没贴编号」
-- 不会互相冲突，编号只在填了的时候才要求不重复。
-- ============================================================================
DROP TABLE IF EXISTS `biz_device`;
CREATE TABLE `biz_device` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `store_id`   BIGINT       NOT NULL                COMMENT '所属门店 ID',
  `name`       VARCHAR(50)  NOT NULL                COMMENT '机台名称，如「拍拍机 1 号」',
  `device_no`  VARCHAR(32)  DEFAULT NULL            COMMENT '资产编号，如 PP-01。现场贴纸编号，便于核对。可空',
  `type_id`    BIGINT       NOT NULL                COMMENT '设备类型 ID，指向 biz_equipment_type',
  `location`   VARCHAR(64)  DEFAULT NULL            COMMENT '位置描述，如「靠窗第二台」',
  `status`     VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状况：NORMAL 良好 / NEEDS_REPAIR 待维护 / MAINTAINING 维护中',
  `sort`       INT          NOT NULL DEFAULT 0      COMMENT '展示顺序，越小越靠前',
  `remark`     VARCHAR(255) DEFAULT NULL            COMMENT '备注，仅运营可见（如「等屏幕配件到货」）',
  `created_at` DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_device_no` (`store_id`, `device_no`),
  KEY `idx_store_status` (`store_id`, `status`),
  KEY `idx_store_sort` (`store_id`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '机台台账';

-- 初始数据：4 台拍拍机 + 2 台抬手乐，覆盖全部三种状况 ——
-- 演示时一屏就能看到「良好 / 待维护 / 维护中」三种标签长什么样。
--
-- 写法说明：门店 ID 与类型 ID 都用子查询取，而不是写死 1 / 2 ——
-- 自增值取决于脚本各段 INSERT 的先后，写死会在脚本顺序调整后静默错位。
-- 若 biz_store 为空，本 INSERT 不插入任何行（JOIN 空表），这是有意的：
-- 门店都没有时，机台无处归属。
--
-- 同 biz_equipment_type，注意显式给出 created_at / updated_at。
INSERT INTO `biz_device` (`store_id`, `name`, `device_no`, `type_id`, `location`, `status`, `sort`, `created_at`, `updated_at`)
SELECT s.id, v.name, v.device_no, t.id, v.location, v.status, v.sort, NOW(), NOW()
  FROM (SELECT id FROM biz_store WHERE deleted = 0 ORDER BY id LIMIT 1) s
  JOIN (
                 SELECT '拍拍机 1 号' AS name, 'PP-01' AS device_no, 'PAIPAI'  AS type_code, '靠窗第一台' AS location, 'NORMAL'       AS status, 10 AS sort
       UNION ALL SELECT '拍拍机 2 号',        'PP-02',              'PAIPAI',                '靠窗第二台',          'NORMAL',              20
       UNION ALL SELECT '拍拍机 3 号',        'PP-03',              'PAIPAI',                '靠墙第三台',          'NORMAL',              30
       UNION ALL SELECT '拍拍机 4 号',        'PP-04',              'PAIPAI',                '靠墙第四台',          'NEEDS_REPAIR',        40
       UNION ALL SELECT '抬手乐 1 号',        'TS-01',              'TAISHOU',               '进门左手边',          'NORMAL',              50
       UNION ALL SELECT '抬手乐 2 号',        'TS-02',              'TAISHOU',               '进门右手边',          'MAINTAINING',         60
       ) v
  JOIN `biz_equipment_type` t ON t.code = v.type_code AND t.deleted = 0;


-- ============================================================================
-- 模块 5：门禁管理
-- ============================================================================
DROP TABLE IF EXISTS `biz_lock`;
CREATE TABLE `biz_lock` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `store_id`       BIGINT       NOT NULL                COMMENT '所属门店 ID',
  `lock_name`      VARCHAR(50)  NOT NULL                COMMENT '锁名称',
  `lock_mac`       VARCHAR(20)  NOT NULL                COMMENT '通通锁 lockMac，锁的物理标识',
  `ttlock_lock_id` BIGINT       DEFAULT NULL            COMMENT '通通锁 /v3/lock/init 返回的 lockId',
  `ttlock_key_id`  BIGINT       DEFAULT NULL            COMMENT '通通锁返回的 keyId（管理员钥匙）',
  `aes_key_str`    VARCHAR(100) DEFAULT NULL            COMMENT '[敏感] 通通锁 aesKeyStr，应加密存储',
  `admin_pwd`      VARCHAR(50)  DEFAULT NULL            COMMENT '[敏感] 管理员密码，应加密存储',
  `pwd_info`       VARCHAR(255) DEFAULT NULL            COMMENT '[敏感] 密码数据，生成密码用，应加密存储',
  `gateway_id`     VARCHAR(50)  DEFAULT NULL            COMMENT '网关 ID。为空表示未接网关，无法远程下发密码（addType=2 不可用）',
  `battery`        INT          DEFAULT NULL            COMMENT '电量百分比',
  `online_status`  VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '在线状态：ONLINE / OFFLINE / UNKNOWN',
  `last_sync_at`   DATETIME     DEFAULT NULL            COMMENT '最后一次与门锁云同步的时刻',
  `created_at`     DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL                COMMENT '更新时间',
  `deleted`        TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lock_mac` (`lock_mac`),
  KEY `idx_store_id` (`store_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '智能门锁';

-- 初始门锁记录：单店单锁，开箱即用。
--
-- 为什么必须有这条：下单要走「下发限时密码」，而密码下发的入参是 lockId。
-- 本表为空时取不到 lockId，整条主链路（下单 → 开门 → 计费）在第一步就断掉。
-- 当前没有「后台添加门锁」的接口（单门店下也就一把锁），所以只能由脚本预置。
--
-- lock_mac 是占位值，不是真实 MAC。接入通通锁时改为锁的实际 MAC，
-- 并补上 ttlock_lock_id / ttlock_key_id / aes_key_str / admin_pwd / pwd_info。
-- gateway_id 留空表示未接网关 —— 真实远程下发需要它（mock 实现不校验）。
INSERT INTO `biz_lock` (`store_id`, `lock_name`, `lock_mac`, `online_status`,
                        `created_at`, `updated_at`, `deleted`)
VALUES (1, '门店大门锁（演示）', 'MOCK0000000001', 'ONLINE', NOW(), NOW(), 0);


-- ============================================================================
-- 模块 8：订单管理（主链路 5→7→8 的枢纽）
--
-- 业务模式：即时制。用户到店点「开门」→ 创建订单并下发限时密码 → 门口输密码进入
--          → 使用中 → 用户点「结束使用」结算 → 离场出门 → 支付（三条线上通道 / 传截图核销）
--
-- 【点击一次，一步到位】：点「开门」这一下同时完成「创建订单 + 下发密码 + 开始计费」。
--   后端不存在「订单已创建但没开门」的挂起态 —— 那种状态既没有用户价值，
--   又留下「下了单不进门」的垃圾订单。因此 status 里不会出现 CREATED，
--   也没有「取消订单」接口：误点一下不想进店，点「结束使用」即可，
--   5 分钟内落在免费档、0 元自动结清（见 status 列的取值说明）。
--
-- 为什么是「先出门后付款」（信任制）：
--   若设计为付款后才解锁出门，人在封闭空间内遇到火灾等紧急情况会被困住，
--   存在消防隐患。因此改为信任制，用可控的欠费风险换取安全。
--
-- 计费起点是 start_time（用户点击「开门」的时刻）而非下单时间 —— 两者现在是同一时刻。
-- 顾客走到门口输密码的那段时间也计入使用时长，这是刻意的：
-- 无需等门锁上报开门记录再对齐时刻，既省下一次门锁云调用（额度 30000 次/月），
-- 也免去了时刻精度与时区对齐的一整块复杂度。
-- ============================================================================
DROP TABLE IF EXISTS `biz_order`;
CREATE TABLE `biz_order` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`        VARCHAR(32)   NOT NULL                COMMENT '业务单号，对外展示',
  `user_id`         BIGINT        NOT NULL                COMMENT '下单用户 ID',
  `store_id`        BIGINT        NOT NULL                COMMENT '使用门店 ID',
  `lock_id`         BIGINT        DEFAULT NULL            COMMENT '门锁 ID，开门时快照',
  `booking_id`      BIGINT        DEFAULT NULL            COMMENT '关联的包场 ID。进店时命中包场才记，结算时据此把包场时段从计费区间剪掉',

  -- 密码相关
  `passcode`        VARCHAR(10)   DEFAULT NULL            COMMENT '下发的限时密码',
  `passcode_start`  DATETIME      DEFAULT NULL            COMMENT '密码生效时间',
  `passcode_end`    DATETIME      DEFAULT NULL            COMMENT '密码失效时间',

  -- 计费区间
  `start_time`      DATETIME      DEFAULT NULL            COMMENT '用户点击开门的时刻，计费起点',
  `end_time`        DATETIME      DEFAULT NULL            COMMENT '离场时刻，进行中为 NULL',
  -- 这两条时长口径不同，不可互相替代：stay_minutes 是「人在店里待了多久」，
  -- day_minutes + night_minutes 是「按分钟收钱的那部分」。包场时段被剪掉、
  -- 宽限 5 分钟也不计入，所以包场 2 小时的单计费时长可以是 0 ——
  -- 拿计费口径当「在店时长」展示，用户会看到「累计时长 0 分钟」。
  `stay_minutes`    INT           DEFAULT NULL            COMMENT '在店时长（分钟）= end_time − start_time，【不参与计费】，仅展示与聚合用。与 day_minutes + night_minutes 的区别：前者含包场时段、含宽限那 5 分钟',

  -- 计费结果（分段存储，供账单分类展示与事后追溯）
  -- ⚠️ 金额列的口径：段金额与合计都是【实收】（已封顶、已含优惠），
  --    discount_amount（月度累计优惠）与 card_free_amount（月卡免除）都只是说明性字段、
  --    互不重叠，也都不可再用「合计 − 优惠」减第二次
  `day_minutes`      INT           DEFAULT NULL            COMMENT '日场时长（分钟）',
  `day_amount`       DECIMAL(10,2) DEFAULT NULL            COMMENT '日场费用（实收，已封顶、已含优惠）',
  `night_minutes`    INT           DEFAULT NULL            COMMENT '夜场时长（分钟）',
  `night_amount`     DECIMAL(10,2) DEFAULT NULL            COMMENT '夜场费用（实收，已封顶、已含优惠）',
  `total_amount`     DECIMAL(10,2) DEFAULT NULL            COMMENT '实收合计 = 日场 + 夜场',
  `discount_amount`  DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '月度累计优惠为本单省下的金额，说明性字段，已包含在 total_amount 中',
  `card_free_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '月卡为本单免掉的金额（不持卡时本单应付的金额，已含月度优惠价），说明性字段，已从 total_amount 中扣除',
  `payable_amount`   DECIMAL(10,2) DEFAULT NULL            COMMENT '应付 = total_amount（预留独立列，供将来优惠券、押金等非计费项）',

  -- 状态
  -- 默认值刻意保留 'CREATED' 而不是改成 'IN_USE'：正常业务里这一列总是被显式赋值，
  -- 默认值只在「有人手工 INSERT 却忘了给 status」时兜底。此时落成 CREATED
  -- 反而是一眼能认出的异常数据，而落成 IN_USE 会伪装成一条「永远在使用中」的订单。
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'CREATED'
                    COMMENT '状态：IN_USE 使用中 / PENDING_PAYMENT 待支付 / PAID 已支付。CREATED 与 CANCELLED 为保留值，当前流程不产生',

  -- 支付（信任制：离场后才付款）
  --   通道按用户浏览器环境自动分流，商户订单号 out_trade_no 由本表 order_no 充当
  `payment_method`  VARCHAR(20)   DEFAULT NULL            COMMENT '支付通道：WXPAY_JSAPI 微信内浏览器 / WXPAY_H5 微信外手机浏览器 / ALIPAY_WAP 支付宝手机网站支付 / QR_UPLOAD 传截图人工核销（降级）',
  `payment_proof`   VARCHAR(255)  DEFAULT NULL            COMMENT '支付截图存储路径，仅 QR_UPLOAD（人工核销降级路径）时必填',
  `payment_no`      VARCHAR(64)   DEFAULT NULL            COMMENT '支付平台交易号：微信 transaction_id / 支付宝 trade_no，三条线上通道均必填',
  `paid_at`         DATETIME      DEFAULT NULL            COMMENT '支付完成时刻',
  `confirmed_by`    BIGINT        DEFAULT NULL            COMMENT '支付核销管理员 ID，为空表示系统自动确认',

  -- 人工调整（用户忘记点「结束使用」时，管理员查监控后手工修正）
  `adjusted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '时长是否经人工调整：0=否 1=是',
  `adjusted_by`     BIGINT        DEFAULT NULL            COMMENT '调整人（管理员 ID）',
  `adjusted_at`     DATETIME      DEFAULT NULL            COMMENT '调整时间',
  `adjust_reason`   VARCHAR(255)  DEFAULT NULL            COMMENT '调整原因，如「用户忘记结束，监控核实 21:30 已离场」',

  `remark`          VARCHAR(255)  DEFAULT NULL            COMMENT '备注',
  `created_at`      DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`      DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`         TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_store_status` (`store_id`, `status`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_adjusted` (`adjusted`),
  KEY `idx_booking_id` (`booking_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '订单';


-- ============================================================================
-- 模块 6：进出记录
--
-- 说明：本表记录的是「开门事件」，而非严格意义上的进与出。
--       封闭空间的出门通常为机械推杠/按钮（消防要求内部免密自由开门），
--       不产生门锁记录，因此系统只能记录到「进门」这一次开门。
--       用户离场时刻由用户手动点「结束使用」确定，异常情况人工复核。
--
--       记录来源不设 QQ_BOT：QQ 机器人自 2026-09-28 起改为只读的信息播报端，
--       不再承载开门、结账等业务操作，因此不会产生该来源的记录。
-- ============================================================================
DROP TABLE IF EXISTS `biz_access_record`;
CREATE TABLE `biz_access_record` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`   BIGINT      DEFAULT NULL            COMMENT '关联订单 ID，管理员开门等无订单场景为空',
  `user_id`    BIGINT      DEFAULT NULL            COMMENT '开门人用户 ID，可为空',
  `store_id`   BIGINT      NOT NULL                COMMENT '门店 ID',
  `lock_id`    BIGINT      NOT NULL                COMMENT '门锁 ID',
  `passcode`   VARCHAR(10) DEFAULT NULL            COMMENT '本次开门所用密码',
  `open_type`  INT         DEFAULT NULL            COMMENT '开门方式，通通锁 lockRecord 原样返回',
  `open_time`  DATETIME    NOT NULL                COMMENT '开门时刻',
  `source`     VARCHAR(20) NOT NULL                COMMENT '记录来源：MOCK 模拟 / TTLOCK 门锁云 / ADMIN 管理员补录',
  `created_at` DATETIME    NOT NULL                COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_lock_time` (`lock_id`, `open_time`),
  KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '进出（开门）记录';


-- ============================================================================
-- 模块 9：优惠管理 —— 月卡
--
-- 业务模式：用户一次性购买月卡，有效期内按时段免费使用。
--           **按购买日起 30 天计**（含首尾），不按自然月 ——
--           否则月末几天买卡的用户只买到两三天，同样的钱买到的东西差一大截。
--
-- 【为什么是两张表：卡 + 购买单】
--   卡是**资产**（有有效期、可查卡包），购买是**交易**（有支付流水、可能放弃）。
--   两者生命周期不同，所以拆开：
--     · 本表只放**真正生效过**的卡 —— start_date / end_date 是这一列 NOT NULL 的原因
--     · 待支付、已关闭的购买尝试留在 biz_monthly_card_order，不污染本表
--   若合成一张表，就必须把 start_date / end_date 放开为可空（未支付的卡没有生效日期），
--   而那是「卡」的核心属性 —— 让核心属性可空，说明表里混进了不是卡的东西。
--
--   （包场 biz_booking 反过来是单表：包场是**一场活动**，排期与付款是同一件事的
--     两个阶段，待支付的包场已经占住了那个时段，不存在「先有付款才有活动」。）
--
-- 与「月度累计消费优惠」的关系（两处口径不同，实现时勿混）：
--   · 月卡期间订单金额为 0，其卡费【不计入】月度累计消费的优惠门槛
--     （月卡本身就是独立优惠政策，不再叠加）
--   · 但购买月卡的支出【计入】sys_user.card_paid（累计月卡充值实付），
--     因为那是用户真掏出去的钱，用于展示与老客回馈
--
-- 详见 docs/月卡设计草案.md
-- ============================================================================
DROP TABLE IF EXISTS `biz_monthly_card`;
CREATE TABLE `biz_monthly_card` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `card_no`        VARCHAR(32)   NOT NULL                COMMENT '卡号，对外展示',
  `user_id`        BIGINT        NOT NULL                COMMENT '持卡用户 ID，月卡绑定本人使用',
  `card_type`      VARCHAR(20)   NOT NULL                COMMENT '卡类型：ALL_DAY 全天 / NIGHT 夜间（夜间仅 22:00–次日 10:00 免费）',
  `price`          DECIMAL(10,2) NOT NULL                COMMENT '购买价格（元），支付时快照，事后调价不影响已售出的卡',
  `start_date`     DATE          NOT NULL                COMMENT '生效日期（= 支付当日）',
  `end_date`       DATE          NOT NULL                COMMENT '失效日期（含当日）= start_date + 29 天',
  `status`         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                   COMMENT '状态：ACTIVE 生效中 / EXPIRED 已过期 / REFUNDED 已退款',
  `payment_method` VARCHAR(20)   DEFAULT NULL            COMMENT '支付通道，取值同 biz_order.payment_method',
  `pay_order_no`   VARCHAR(32)   DEFAULT NULL            COMMENT '产生本卡的购买单号（biz_monthly_card_order.order_no）',
  `paid_at`        DATETIME      DEFAULT NULL            COMMENT '支付时刻，精确到时刻，供对账',
  `created_at`     DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_no` (`card_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_end_date` (`end_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '月卡';


-- ============================================================================
-- 模块 9：优惠管理 —— 月卡购买单
--
-- 记录「用户想买一张月卡」这件事，从发起到支付成功或关闭。
-- 支付目标是**本表**而非月卡本身：支付回调按商户订单号反查，要求目标先落库，
-- 而「还没付钱的卡」不该出现在月卡表里（见上方「为什么是两张表」）。
--
--   POST /api/cards/purchases  →  本表落一条 PENDING_PAYMENT（order_no 即 out_trade_no）
--          │
--     支付回调（同一事务两跳）
--          ├─ ① 本表 → PAID（带 status 守卫，幂等第一道）
--          └─ ② 往 biz_monthly_card 插一张 ACTIVE 卡（start_date = 支付当日）
--
-- 三张收款表（biz_order / biz_booking / 本表）的支付字段口径保持一致。
-- 卡费计入 sys_user.card_paid，不计入月度累计消费的优惠门槛。
-- ============================================================================
DROP TABLE IF EXISTS `biz_monthly_card_order`;
CREATE TABLE `biz_monthly_card_order` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`       VARCHAR(32)   NOT NULL                COMMENT '购买单号，同时是商户订单号 out_trade_no',
  `user_id`        BIGINT        NOT NULL                COMMENT '购买人用户 ID',
  `card_type`      VARCHAR(20)   NOT NULL                COMMENT '卡类型：ALL_DAY 全天 / NIGHT 夜间',
  `price`          DECIMAL(10,2) NOT NULL                COMMENT '应付金额（元），下单时快照',
  `status`         VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT'
                   COMMENT '状态：PENDING_PAYMENT 待支付 / PAID 已支付 / CLOSED 已关闭（用户取消或超时未付）',
  `payment_method` VARCHAR(20)   DEFAULT NULL            COMMENT '支付通道，取值同 biz_order.payment_method',
  `payment_no`     VARCHAR(64)   DEFAULT NULL            COMMENT '支付平台交易号：微信 transaction_id / 支付宝 trade_no',
  `paid_at`        DATETIME      DEFAULT NULL            COMMENT '支付完成时刻',
  `created_at`     DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '月卡购买单';


-- ============================================================================
-- 公告（不对应论文某一章，与 common 公共层同性质）
--
-- ⚠️ 这是一条【消息流】，不是一份【状态快照】。这一点决定了本表几乎所有的设计：
--
--   · 只增不改 —— 机台每次状况变化各产生一条。修好不会把「转为维护中」那条
--     改掉或删掉，而是再产生一条「转为良好」。首页公告栏读起来是一段历史。
--   · 没有生效/失效时刻 —— 一条消息的「有效期」是说不通的。
--     「3 号机转维护中」发生在那一刻，它不需要「从明天起生效」，也不会
--     「下周三自动失效」。发生时刻就是 created_at。
--   · 没有唯一键约束 —— 同一台机台可以反复出现在公告里，
--     幂等在这里恰恰是要避免的行为。
--   · 最新的在最上面，靠 id 倒序。没有置顶、权重、排序值那一套——
--     消息流里「我想让这条排前面」不是一个真实需求。
--
-- 两类公告靠 publish_mode 区分：
--   MANUAL 管理员手写 —— source_type / source_id 为 NULL，可改可删
--   AUTO   系统自动   —— 记录已发生的事实，只读（改了等于篡改历史）
--
-- ⚠️ 包场【不在这里】：包场是「未来的安排」，公告是「已发生的事」。
--    两者混在一条流里，用户分不清哪条是通知、哪条是日程；而且日程会随改期变动，
--    消息流只增不改，改期后旧的那条永远对不上。包场走 GET /api/store/bookings
--    的「包场时间表」，直接查 biz_booking，不落本表。
--
-- ⚠️ 也不建「停业公告」：停业已有 StoreStatusVo 在管（STATUS_CLOSED），
--    那条路径的措辞边界定好了（只说「暂停营业」，不说原因），不在这里另开一条。
-- ============================================================================
DROP TABLE IF EXISTS `biz_notice`;
CREATE TABLE `biz_notice` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`        VARCHAR(100)  NOT NULL                COMMENT '公告标题，一句话。首页公告栏显示的就是它',
  `content`      VARCHAR(500)  DEFAULT NULL            COMMENT '公告正文，可为空。自动公告恒为 NULL，只有手写公告才有',
  `publish_mode` VARCHAR(20)   NOT NULL                COMMENT '发布方式：AUTO 系统自动 / MANUAL 管理员手写。见 NoticePublishMode',
  `source_type`  VARCHAR(20)   DEFAULT NULL            COMMENT '自动公告的来源类型：DEVICE 机台；手写公告为 NULL。见 NoticeSourceType',
  `source_id`    BIGINT        DEFAULT NULL            COMMENT '自动公告的来源记录 ID（机台 ID）；手写公告为 NULL。用来追溯「这条是哪台机器产生的」',
  `created_by`   BIGINT        DEFAULT NULL            COMMENT '发布人（管理员 ID）；自动公告为 NULL',
  `created_at`   DATETIME      NOT NULL                COMMENT '创建时间，同时也是这条消息的发生时刻',
  `updated_at`   DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`      TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删。只有手写公告会被删',
  PRIMARY KEY (`id`),
  -- 用户端取最新几条就是 ORDER BY id DESC LIMIT n，主键索引直接服务。
  -- 这条二级索引给后台按来源追溯用（「这台机器都发生过什么」），
  -- 注意它是【普通索引】不是唯一索引 —— 同一台机台会有很多条
  KEY `idx_source` (`source_type`, `source_id`),
  -- 结构上钉住「手写无来源、自动必有来源」：两个来源列要么全空、要么全不空。
  -- 应用层已经这样保证（Service 方法体里硬编码），这里是数据库侧的第二道防线 ——
  -- 将来新增第二条写入路径时，绕过应用层也绕不过这里
  CONSTRAINT `ck_notice_source` CHECK (
      (`publish_mode` = 'MANUAL' AND `source_type` IS NULL     AND `source_id` IS NULL)
   OR (`publish_mode` = 'AUTO'   AND `source_type` IS NOT NULL AND `source_id` IS NOT NULL)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '公告（消息流）';


-- ============================================================================
-- 商品（不对应论文某一章，与 common 公共层同性质）
--
-- 与月卡同构的**两张表**：一张放「卖什么」（资产的定义），一张放「买过什么」（交易）。
--
--   POST /api/product-orders  →  本表落一条 PENDING_PAYMENT（order_no 即 out_trade_no）
--          │
--     支付回调（同一事务两跳）
--          ├─ ① 本表 → PAID（带 status 守卫，幂等第一道）
--          └─ ② 条件 UPDATE 扣减 biz_product.stock（WHERE stock >= 数量）
--
-- ⚠️ 库存是「支付成功时才扣」，不是下单时预占。这是已知的取舍：
--    严格方案是下单预占、超时释放。取「支付时才扣」的风险是两人同时买最后一件
--    都能下单成功，后付款的那个没货可拿。缓解办法是下单时按
--    「可售量 = 库存 − 未支付的待支付单数」做一次软检查（不锁库存，
--    所以仍有竞态窗口）；最后一道防线是上面那个条件 UPDATE ——
--    受影响行数为 0 就记 error 日志转人工，绝不在回调里抛异常
--    （那会让支付平台不断重推一笔永远处理不了的通知）。
--
-- ⚠️ 超时的待支付单**不再占库存**（软检查按 created_at 过滤），
--    但**不会自动关闭** —— 用户仍然可以把它付掉。这与月卡购买单刻意不同：
--    月卡是「一人一卡」，未关闭的旧单会把用户自己卡死，所以必须关；
--    商品可以买多笔，关掉旧单反而是替用户做了「不买了」的决定。
--
-- 四张收款表（biz_order / biz_booking / biz_monthly_card_order / 本表）
-- 的支付字段口径保持一致，回调代码不区分自己处理的是哪一种。
--
-- 商品消费计入 sys_user.order_paid（与房间使用费同类），不计入 card_paid。
-- ============================================================================
DROP TABLE IF EXISTS `biz_product`;
CREATE TABLE `biz_product` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(100)  NOT NULL                COMMENT '商品名称',
  `cover`       VARCHAR(255)  DEFAULT NULL            COMMENT '封面图地址（站内相对路径），可为空',
  `description` VARCHAR(500)  DEFAULT NULL            COMMENT '商品描述',
  `price`       DECIMAL(10,2) NOT NULL                COMMENT '售价（元）',
  `stock`       INT           NOT NULL DEFAULT 0      COMMENT '当前库存。支付成功时才扣（条件 UPDATE），下单时只做软检查',
  `enabled`     TINYINT       NOT NULL DEFAULT 1      COMMENT '是否上架：0 下架 1 上架。下架的商品不在用户端列表里，也不能下单，但详情仍可打开',
  `sort_no`     INT           NOT NULL DEFAULT 0      COMMENT '排序值，越小越靠前',
  `created_at`  DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`  DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`     TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  -- 用户端列表就是「上架的、按 sort_no 排」，这条索引直接服务。
  -- 排序时 id 兜底：sort_no 由管理员随手填、撞值常见，只按它排会让同值行
  -- 的先后由存储引擎决定 —— 翻页时表现为「某件商品没出现过」
  KEY `idx_enabled_sort` (`enabled`, `sort_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '实体商品';

DROP TABLE IF EXISTS `biz_product_order`;
CREATE TABLE `biz_product_order` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`       VARCHAR(32)   NOT NULL                COMMENT '购买单号，同时是商户订单号 out_trade_no。前缀 PD',
  `user_id`        BIGINT        NOT NULL                COMMENT '购买人用户 ID',
  `product_id`     BIGINT        NOT NULL                COMMENT '商品 ID。商品被删除后这里仍指向原 ID，名称靠 product_name 快照还原',
  `product_name`   VARCHAR(100)  NOT NULL                COMMENT '商品名称快照 —— 商品改名后历史订单仍显示当时的名字',
  `unit_price`     DECIMAL(10,2) NOT NULL                COMMENT '下单时单价快照（元）。事后调价不影响已售出的单',
  `quantity`       INT           NOT NULL DEFAULT 1      COMMENT '数量',
  `amount`         DECIMAL(10,2) NOT NULL                COMMENT '应付金额（元）= unit_price × quantity，下单时算好存下',
  `status`         VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT'
                   COMMENT '状态：PENDING_PAYMENT 待支付 / PAID 已支付（到店自取，无核销流程）/ CLOSED 已关闭',
  `payment_method` VARCHAR(20)   DEFAULT NULL            COMMENT '支付通道，取值同 biz_order.payment_method',
  `payment_no`     VARCHAR(64)   DEFAULT NULL            COMMENT '支付平台交易号：微信 transaction_id / 支付宝 trade_no',
  `paid_at`        DATETIME      DEFAULT NULL            COMMENT '支付完成时刻',
  `created_at`     DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  -- 两个用途：用户端「我的订单」按 user_id 查；后台按 user_id 筛。
  -- 带 status 是让「只看待支付的」这类筛选用得上索引
  KEY `idx_user_status` (`user_id`, `status`),
  -- 软检查要用：按商品统计「未超时的待支付单」有多少笔。
  -- 没有它，每算一次可售量都是全表扫描
  KEY `idx_product_status_created` (`product_id`, `status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '实体商品购买单';


SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 后续模块建表时追加于此：
--   模块 12 系统日志     sys_log
--
-- 模块 9 不建优惠规则表：月卡已由 biz_monthly_card + biz_monthly_card_order 承载，
-- 「满 200 元后按优惠价」的门槛与两套单价外置在 application.properties
-- （uspace.billing.monthly-discount.*），改活动不必改表、也不必发版。
--
-- 公告（biz_notice）不对应论文某一章，与 common 公共层同性质 ——
-- 它是横跨设备、包场、门店三条线的统一信息出口，独立成包正是为了不被任何一条线绑住。
-- 详见上面建表处与 backend 的 notice 包说明。
--
-- 商品（biz_product + biz_product_order）同理不对应论文某一章 ——
-- 它是独立于时长计费之外的第二类收入，与月卡同形（自己管目录与购买单，
-- 收款处理器住在 order 包）。详见上面建表处与 backend 的 product 包说明。
-- ============================================================================
