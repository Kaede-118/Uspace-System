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
-- 包场时段内不计费（包场费已预付），时段结束后仍逗留的部分按普通规则计时；
-- 订单如何挂接本表留到模块 8 细化。
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


-- ============================================================================
-- 模块 8：订单管理（主链路 5→7→8 的枢纽）
--
-- 业务模式：即时制。用户到店下单 → 远程下发密码 → 开门进入（封闭空间）
--          → 使用中 → 用户手动点「结束使用」→ 生成订单 → 离场出门
--          → 支付（H5 线上支付 / 扫收款码传截图）
--
-- 为什么是「先出门后付款」（信任制）：
--   若设计为付款后才解锁出门，人在封闭空间内遇到火灾等紧急情况会被困住，
--   存在消防隐患。因此改为信任制，用可控的欠费风险换取安全。
--
-- 计费起点是 start_time（用户点击「开门」的时刻）而非下单时间。
-- 点开门即开始计费，顾客走到门口输密码的那段时间也计入使用时长 —— 这是刻意的：
-- 无需等门锁上报开门记录再对齐时刻，既省下一次门锁云调用（额度 30000 次/月），
-- 也免去了时刻精度与时区对齐的一整块复杂度。
-- ============================================================================
DROP TABLE IF EXISTS `biz_order`;
CREATE TABLE `biz_order` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`        VARCHAR(32)   NOT NULL                COMMENT '业务单号，对外展示',
  `user_id`         BIGINT        NOT NULL                COMMENT '下单用户 ID',
  `store_id`        BIGINT        NOT NULL                COMMENT '使用门店 ID',
  `lock_id`         BIGINT        DEFAULT NULL            COMMENT '门锁 ID，下单时快照',

  -- 密码相关
  `passcode`        VARCHAR(10)   DEFAULT NULL            COMMENT '下发的限时密码',
  `passcode_start`  DATETIME      DEFAULT NULL            COMMENT '密码生效时间',
  `passcode_end`    DATETIME      DEFAULT NULL            COMMENT '密码失效时间',

  -- 计费区间
  `start_time`      DATETIME      DEFAULT NULL            COMMENT '用户点击开门的时刻，计费起点',
  `end_time`        DATETIME      DEFAULT NULL            COMMENT '离场时刻，进行中为 NULL',

  -- 计费结果（分段存储，供账单分类展示与事后追溯）
  -- ⚠️ 三个金额列的口径：段金额与合计都是【实收】（已封顶、已含优惠），
  --    discount_amount 只是说明性字段，不可再用「合计 − 优惠」减第二次
  `day_minutes`     INT           DEFAULT NULL            COMMENT '日场时长（分钟）',
  `day_amount`      DECIMAL(10,2) DEFAULT NULL            COMMENT '日场费用（实收，已封顶、已含优惠）',
  `night_minutes`   INT           DEFAULT NULL            COMMENT '夜场时长（分钟）',
  `night_amount`    DECIMAL(10,2) DEFAULT NULL            COMMENT '夜场费用（实收，已封顶、已含优惠）',
  `total_amount`    DECIMAL(10,2) DEFAULT NULL            COMMENT '实收合计 = 日场 + 夜场',
  `discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '本单优惠金额，说明性字段，已包含在 total_amount 中',
  `payable_amount`  DECIMAL(10,2) DEFAULT NULL            COMMENT '应付 = total_amount（预留独立列，供将来优惠券、押金等非计费项）',

  -- 状态
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'CREATED'
                    COMMENT '状态：CREATED 已创建 / IN_USE 使用中 / PENDING_PAYMENT 待支付 / PAID 已支付 / CANCELLED 已取消',

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
  KEY `idx_adjusted` (`adjusted`)
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
  `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `card_no`      VARCHAR(32)   NOT NULL                COMMENT '卡号，对外展示',
  `user_id`      BIGINT        NOT NULL                COMMENT '持卡用户 ID，月卡绑定本人使用',
  `card_type`    VARCHAR(20)   NOT NULL                COMMENT '卡类型：ALL_DAY 全天 / NIGHT 夜间（仅 22:00–10:00 免费）',
  `price`        DECIMAL(10,2) NOT NULL                COMMENT '购买价格（元）',
  `start_date`   DATE          NOT NULL                COMMENT '生效日期（购买当日）',
  `end_date`     DATE          NOT NULL                COMMENT '失效日期（含当日）= start_date + 29 天',
  `status`       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                 COMMENT '状态：ACTIVE 生效中 / EXPIRED 已过期 / REFUNDED 已退款',
  `pay_order_no` VARCHAR(32)   DEFAULT NULL            COMMENT '购买时的支付单号，复用模块 8 的支付服务',
  `paid_at`      DATETIME      DEFAULT NULL            COMMENT '支付时刻，支付成功即生效',
  `created_at`   DATETIME      NOT NULL                COMMENT '创建时间',
  `updated_at`   DATETIME      NOT NULL                COMMENT '更新时间',
  `deleted`      TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card_no` (`card_no`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_end_date` (`end_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '月卡';


SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 后续模块建表时追加于此：
--   模块 4  设备管理     biz_device（具体某台机器，每台指向 biz_equipment_type）
--                        —— biz_equipment_type 字典表已建，见上方
--   模块 9  优惠管理     biz_promotion_rule
--                        （优惠规则表，视需要而定 —— 当前计费与优惠参数
--                          已外置在 application.properties，不必再落库）
--   模块 12 系统日志     sys_log
-- ============================================================================
