做T回本 / 补仓 / 买点扫描 功能设计与实现规格
版本 v1 · 2026-11-07 · 数据源：本地 MySQL stock_daily（约200个交易日日线）· 用法：整篇贴给代码模型，按「十三、实现顺序」分阶段生成 Java 后端与前端。
一、目标与范围
三个功能，共用同一个策略引擎：
1. 持仓回本模拟（Simulate）：输入股票、买入日、买入价/数量、总资金、可支配资金。系统用历史日线逐日回放：下跌触发分档补仓，在可卖底仓上做T降成本，回本或达标后离场。输出每日持仓、摊薄成本、盈亏曲线和全部交易明细，并对比「买入持有」「只补仓不做T」两个基准，看清做T到底贡献了多少。
2. 买入日扫描（Scan）：对同一只股票，逐日假设「当天买入」并跑同一套策略，统计哪些买入日、哪些买入特征（RSI、偏离均线、前期涨跌幅等）结果更好，给出推荐买点，以及当前是否处于有利条件。
3. 次日操作计划（Plan）：给定当前真实持仓（数量、可卖数量、成本、现金），输出下一个交易日的补仓价和数量、做T买卖价和数量、回本卖出价、止损价。
   v1 不做：分时或逐笔回测、融资融券、除权除息精确处理、机器学习预测、自动下单。v1 是「规则 + 波动率估计」的回测与计划工具，不是保证盈利的预测器。
   二、必须先遵守的现实约束
   约束
   规则
   对模型的影响
   T+1
   当日买入的股票次日才能卖
   做T只能动「开盘时已持有的底仓」；买入当天不能卖；sellableQty = 当日开盘时的持仓数
   最小单位
   买入按 100 股整数倍
   所有数量向下取整到 100 的倍数，不足 100 股不操作
   价格最小变动
   0.01 元
   挂单价四舍五入到分
   佣金
   默认万2.5，最低 5 元，双向
   参数可配，以券商实际为准
   印花税
   卖出单边 0.05%（2023-08-28 起减半，2026 年仍执行）
   仅卖出收取
   过户费、规费
   过户费默认 0.001% 双向；经手费加证管费合计默认约 0.005% 双向
   参数可配
   做T一个往返的成本
   约 0.11% 加上最低佣金影响
   目标价差必须明显高于成本；金额太小时最低 5 元佣金会吃掉利润
   涨跌停
   代码 60、00 开头为 10%；30 开头为 20%；is_st=1 为 5%
   挂单价限制在涨跌停范围内；一字板（high=low）视为无法成交
   停牌
   tradestatus=0
   当日不成交，只做估值
   日线粒度
   只有 OHLC，不知道盘中先高后低
   做T采用「双向限价挂单」，成交判定与先后顺序无关（见 5.3）
   前复权
   默认 adjustflag=2
   区间内如有分红送转，前复权价和真实成交价有偏差，v1 忽略，结果页给出提示
   三、数据层
   • 查询：SELECT trade_date, open, high, low, close, preclose, volume, amount, amplitude, pct_chg, turnover, tradestatus, is_st FROM stock_daily WHERE code=? AND market=? AND adjustflag=2 AND trade_date BETWEEN ? AND ? ORDER BY trade_date，命中索引 idx_daily_resume。
   • 市场推断：用户只输入 6 位代码时，6 开头为 SH，0 或 3 开头为 SZ。
   • 预热：指标至少需要 20 根K线，约 200 天数据里可回放区间约 180 天。买入日早于第 21 个交易日时提示「历史数据不足」。
   • 缺失处理：open/high/low/close 任一为空，或 tradestatus=0，该日视为停牌。
   • 买入日不是交易日时，顺延到下一个交易日。
   四、指标（IndicatorSet，每个交易日一份）
   所有指标只用该日及以前的数据计算。策略在 t 日只能读取 t-1 及以前的指标。
   • atrPct：TR = max(high-low, abs(high-preclose), abs(low-preclose))，ATR14 为 14 日均 TR，atrPct = ATR14 / close
   • ma5, ma10, ma20，以及 ma20Slope（MA20 相对 5 日前的变化率）
   • rsi14、布林带 %B（20 日，2 倍标准差）
   • volRatio = 当日成交量 / 前 5 日均量
   • ret5, ret20 近 5、20 日涨跌幅；devMa20 = close/MA20 - 1
   • avgAmp20 = 近 20 日 amplitude 均值（展示用）
   五、核心引擎
   5.1 持仓状态 PositionState
   qty（持仓股数）、sellableQty、cash、cumBuyCost（累计买入总支出，含费用）、cumSellNet（累计卖出净收入，扣费后）、addCount、lastAddDate、pending（遗留头寸，见 5.3）、maxInvested（cumBuyCost - cumSellNet 的历史最大值）。
   派生量：
   • 摊薄成本 dilutedCost = (cumBuyCost - cumSellNet) / qty。做T赚的钱会让它下降，这就是「做T降成本」的量化体现。
   • 总盈亏 totalPnL = qty * close + cumSellNet - cumBuyCost。回本 = totalPnL >= 0。
   • 净值 equity = cash + qty * close。
   5.2 每日流程（防前视是硬性要求）
   for t in 买入日之后的每个交易日:
1. 若 t 停牌: 只做估值, continue
2. sellableQty = qty                       // 开盘时持有的全部股数, 当日买入的不算
3. orders = StrategyEngine.planOrders(state, indicators[t-1], bars[<= t-1])   // 只看昨天及以前
4. fills  = ExecutionSimulator.execute(orders, bar[t], state)                 // 只有这里才看当日K线
5. 应用成交, 更新 state, 写交易明细
6. 收盘估值, 写每日快照
7. 清仓 / 到 endDate / 超过 maxHoldDays 则结束
   同日冲突规则（保守处理）：优先级为 止损与时间止损 > 回本离场 > 补仓 > 遗留头寸处理 > 做T。资金按优先级预留：先扣补仓预算，剩余才给做T的买入腿。清仓成交的当天，其余挂单全部作废。
   5.3 三个策略模块
   A. 分档补仓（Add）
   • 触发价 addPrice_k = dilutedCost × (1 - step_k)，默认三档 5%、10%、15%（也可改为按 ATR 的倍数）。dilutedCost <= 0 时不补仓。
   • 前置条件：addCount < maxAddTimes；距上次补仓至少 addMinIntervalDays；总持仓市值补仓后不超过 totalCapital × maxPositionRatio；可选的企稳确认 addConfirm（NONE 或 STOP_FALL，后者要求 t-1 日收盘高于开盘或 RSI14 较前一日回升）。
   • 金额：补仓预算 = availableCash × (1 - tReserveRatio)，各档按 addRatios 分配（默认 0.3、0.3、0.4）。数量为 floor(金额 / addPrice / 100) × 100。不做马丁式越补越多，档位金额默认等额或递增不超过预算分配。
   • 成交：open <= addPrice 按开盘价；否则 low <= addPrice 按 addPrice；否则不成交。
   B. 做T（SwingT，双向限价挂单）
   每个交易日，在可卖底仓上同时挂两张单，基准为昨收 C0：
   • 卖单：sellLimit = round2(C0 × (1 + band))，数量 N，卖的是底仓（满足 T+1）
   • 买单：buyLimit = round2(C0 × (1 - band))，数量 N，用现金买
   • band = clamp(kAtr × atrPct[t-1], bandMin, bandMax)，其中 bandMin = (roundTripCostRate + minNetProfitRate) / 2
   • N = floor(min(sellableQty × tRatio, 可用做T现金 / buyLimit, 成交额 × liquidityParticipation / 价格) / 100) × 100
   • 若 N × (sellLimit - buyLimit) - 全部费用 < minTNetProfitYuan，当日不做T
   • 价格需落在 [跌停价, 涨停价] 内；一字板、停牌当日不做T
   成交判定（无需知道盘中先后顺序）：
   情况
   判定
   结果
   两腿都成交
   high >= sellLimit 且 low <= buyLimit
   完整往返，已实现收益 = N × (卖价 - 买价) - 费用。无论先后，净效果都是「卖 N 在高位、买 N 在低位」，持仓不变
   只有卖腿成交
   仅 high >= sellLimit
   持仓减少 N，登记 pendingBuyback
   只有买腿成交
   仅 low <= buyLimit
   持仓增加 N（次日可卖），登记 pendingSell
   都不成交
   —
   无变化
   跳空：open >= sellLimit 时卖腿按开盘价成交，open <= buyLimit 时买腿按开盘价成交。
   遗留头寸处理（pending 存在时不开新的做T）：
   • pendingBuyback：挂回补限价 refPx × (1 - roundTripCostRate - minNetProfitRate)，low 触及即成交；超过 maxCarryDays 仍未成交，按收盘价强制回补（加滑点）。
   • pendingSell：挂卖出限价 refPx × (1 + roundTripCostRate + minNetProfitRate)，high 触及即成交；超过 maxCarryDays 默认并入持仓（等同一次补仓），可配置为收盘强平。
   C. 离场（Exit）
   • 回本或达标离场：求最小价格 p 使 qty × p - sellFees(qty × p) - (cumBuyCost - cumSellNet) >= targetProfitRate × maxInvested。targetProfitRate = 0 就是「回本即走」。成交：open >= p 按开盘价，否则 high >= p 按 p。p 可能低于当前价（做T已赚回本金），此时次日开盘即走。
   • 止损：totalPnL <= -maxLossRate × maxInvested 时，下一个交易日开盘全部卖出（加滑点）。
   • 时间止损：持仓超过 maxHoldDays 个交易日，下一个交易日开盘全部卖出。
   • 卖出只能动 sellableQty。
   5.4 费用与滑点
   fee(amount, isSell) = max(minCommission, amount*commissionRate)
   + amount*transferFeeRate + amount*otherFeeRate
   + (isSell ? amount*stampTaxRate : 0)
   roundTripCostRate  ≈ 两边佣金 + 印花税 + 两边过户费和规费   // 默认约 0.11%
   限价成交按限价；市价类成交（开盘价、收盘强平）按 slippageBp 加滑点（买入加、卖出减）。
   六、默认参数（全部可在前端高级设置里覆盖）
   参数
   默认
   说明
   commissionRate / minCommission
   0.00025 / 5
   佣金，最低 5 元
   stampTaxRate
   0.0005
   卖出单边
   transferFeeRate / otherFeeRate
   0.00001 / 0.00005
   双向，以券商实际为准
   slippageBp
   1
   仅市价类成交
   maxPositionRatio
   0.6
   持仓市值占总资金上限，只限制补仓
   tReserveRatio
   0.3
   可支配资金里留给做T的比例，补仓不动用
   addSteps / addRatios
   5%,10%,15% / 0.3,0.3,0.4
   相对摊薄成本的跌幅档位与预算分配
   maxAddTimes / addMinIntervalDays
   3 / 2
   补仓次数与间隔
   addConfirm
   NONE
   或 STOP_FALL
   tRatio
   0.5
   单次做T数量占可卖底仓比例上限
   kAtr / bandMax
   0.4 / 5%
   做T价带 = 0.4 倍 ATR%，上限 5%
   minNetProfitRate
   0.003
   单次往返净利润下限（占价格）
   minTNetProfitYuan
   20
   单次往返净利润下限（元）
   liquidityParticipation
   0.01
   单笔数量不超过当日成交额的 1%
   maxCarryDays / carryPolicy
   3 / BUYBACK_FORCE, SELL_ABSORB
   遗留头寸处理
   targetProfitRate
   0
   0 为回本即走，也可设 3% 等
   maxLossRate / maxHoldDays
   0.2 / 60
   止损与时间止损
   buyPriceMode
   CLOSE
   未填买入价时用买入日收盘价；扫描时用 NEXT_OPEN
   七、买入日扫描（Scan）
1. 对 [第21个交易日, 最后一天 - holdDays] 内每个交易日 d：以 d 的下一交易日开盘价买入（避免前视），初始仓位 = totalCapital × initPositionRatio（默认 0.3），现金为 availableCash，用同一套策略回放 holdDays（默认 20）个交易日，在窗口最后一天按收盘价估值。
2. 每个 d 产出：finalPnLPct（含做T）、buyHoldPct、onlyAddPct、maxDrawdown、recoverDays（首次 totalPnL >= 0 的天数，未回本为空）、tCount、tNetProfit、feeTotal，以及买入日特征 ret5, ret20, devMa20, rsi14, percentB, volRatio, atrPct。
3. 特征分桶统计：每个特征分 3 到 5 档，输出每档的样本数、胜率、平均收益、平均最大回撤。样本数小于 15 的档标注「样本不足」。
4. 推荐：给出综合得分最高的若干买入日；用今日（最新一根K线）的特征落入的分桶历史统计，回答「现在是不是有利买点」。
5. 稳健性：把时间轴按前 70% 和后 30% 拆开，各算一遍统计，两段结论方向不一致时在页面标红提示。
   务必提示用户：窗口相互重叠，有效样本远小于窗口数；约 200 天只代表这只股票最近一段行情，分桶结论只能当参考，不能当规律。
   八、次日操作计划（Plan）
   输入当前真实状态：qty、sellableQty、avgCost（券商显示的持仓成本）、cash、totalCapital、asOfDate（默认最新交易日）。直接调用 StrategyEngine.planOrders，不需要K线撮合。输出：
   • 补仓挂单：价格、数量、占用资金、触发依据（距摊薄成本跌幅档位）
   • 做T：买价、卖价、数量、预期净利润、成交所需条件（low <= 买价 且 high >= 卖价）
   • 回本或达标卖出价；止损价
   • 各挂单为什么触发或为什么不触发（文字解释，如「已达 maxAddTimes」「仓位已达上限」「预期净利润低于下限」）
   • 提醒：计划基于昨日及以前数据，不保证成交，也不保证盈利
   九、输出指标（Summary）
   总盈亏（元、占 maxInvested 与占 totalCapital 两个口径）、最大回撤（按净值曲线）、回本日期与耗时、持仓天数、最大占用资金与资金利用率、补仓次数与金额、做T：完整往返次数 / 遗留次数 / 净利润 / 胜率、手续费合计、摊薄成本曲线、三条净值曲线（含做T策略、只补仓不做T、买入持有）。
   十、数据库表（MySQL，沿用 stock_daily 风格）
   create table t_sim_task (
   id bigint auto_increment primary key,
   mode varchar(10) not null comment 'SIMULATE/SCAN/PLAN',
   code varchar(10) not null, market char(2) not null,
   buy_date date null, buy_price decimal(12,4) null, init_qty int null,
   total_capital decimal(16,2) not null, available_cash decimal(16,2) not null,
   params_json json not null comment '本次生效的全部参数',
   summary_json json null,
   created_at datetime default current_timestamp not null,
   index idx_task_code (code, market, created_at)
   );
   create table t_sim_daily (
   task_id bigint not null, trade_date date not null,
   close decimal(12,4), qty int, sellable_qty int, cash decimal(16,2),
   equity decimal(16,2), diluted_cost decimal(12,4), total_pnl decimal(16,2),
   events varchar(64) comment '当日事件标记: ADD,T,EXIT,STOP',
   primary key (task_id, trade_date)
   );
   create table t_sim_trade (
   id bigint auto_increment primary key,
   task_id bigint not null, trade_date date not null,
   action varchar(12) not null comment 'INIT/ADD/T_SELL/T_BUY/BUYBACK/SELL_PENDING/EXIT/STOP',
   price decimal(12,4), qty int, amount decimal(16,2), fee decimal(12,2),
   realized_pnl decimal(16,2) null, note varchar(255) null,
   index idx_trade_task (task_id, trade_date)
   );
   create table t_scan_result (
   task_id bigint not null, buy_date date not null,
   final_pnl_pct decimal(10,4), buy_hold_pct decimal(10,4), only_add_pct decimal(10,4),
   max_drawdown decimal(10,4), recover_days int null, t_count int, t_net_profit decimal(16,2),
   features_json json,
   primary key (task_id, buy_date)
   );
   十一、后端接口
   公共请求字段：code、market（可空，后端推断）、buyDate、buyPrice（可空）、initQty 或 initAmount（二选一）、totalCapital、availableCash、endDate（可空）、params（对象，只传需要覆盖的参数）。
   接口
   作用
   返回
   POST /api/t/simulate
   持仓回本模拟
   taskId、summary、daily[]、trades[]、benchmarks、warnings[]
   POST /api/t/scan-entry
   买入日扫描（额外字段 holdDays、initPositionRatio）
   taskId、rows[]（每个买入日一行）、featureBuckets[]、recommendations[]、currentSignal、stability
   POST /api/t/next-plan
   次日计划（额外字段 qty、sellableQty、avgCost、cash）
   orders[]、explanations[]、indicators
   GET /api/t/params/default
   默认参数及说明（前端据此渲染高级设置）
   参数列表
   GET /api/t/tasks/{id}
   回看历史任务
   同对应接口
   参数校验：availableCash <= totalCapital；初始买入金额加上手续费不得超过 totalCapital；code 在 stock_daily 中必须有数据；数据不足返回明确的 warnings，不要抛 500。
   十二、前端页面
   推荐 ECharts。三个标签页：
1. 回本模拟：左侧表单（股票代码、买入日期、买入价（可空）、数量或金额、总资金、可支配资金、折叠的高级参数）；右侧结果。顶部指标卡（总盈亏、回本日、最大回撤、做T次数与净利润、手续费、与两个基准的差值）；主图为K线 + MA，叠加摊薄成本线，并用不同标记标出 买入 / 补仓 / 做T完成 / 遗留 / 清仓 / 止损；副图为三条净值曲线；下方为交易明细表（可按动作筛选）和每日快照表。
2. 买点扫描：每个买入日的 finalPnLPct 柱状图（红涨绿跌，点击可跳转到该日的回本模拟）；特征分桶表（样本数、胜率、平均收益、回撤，样本不足灰显）；推荐买入日列表；「当前信号」卡片；稳健性提示。
3. 次日计划：表单输入真实持仓；结果为挂单卡片列表（价格、数量、预期净利润、触发解释）。
   页面固定展示风险提示条：「基于历史日线的规则回测，不含分时数据，历史表现不代表未来，仅供研究参考」。所有金额保留两位小数，涨红跌绿，可导出交易明细 CSV。
   十三、Java 实现结构与顺序
   框架按项目现有技术栈（Spring Boot 优先）。金额一律 BigDecimal；指标用 double。
   domain/     Bar, Params, PositionState, Order, Fill, TradeRecord, Pending
   indicator/  IndicatorService        // 一次性算出整段 IndicatorSet 列表
   engine/     FeeCalculator           // 费用、滑点、往返成本
   StrategyEngine          // planOrders(state, ind[t-1], history) -> List<Order>, 纯函数
   ExecutionSimulator      // execute(orders, bar[t], state) -> List<Fill>
   Backtester              // 5.2 的每日循环, 产出 daily/trades/summary
   EntryScanner            // 逐日调用 Backtester 并做特征分桶
   NextDayPlanner          // 复用 StrategyEngine.planOrders
   repo/       StockDailyRepository    // 第三节的查询
   web/        TController + 请求/响应 DTO
   实现顺序（每步都先写单元测试）：
1. FeeCalculator 与 IndicatorService
2. StrategyEngine 三个模块与 ExecutionSimulator
3. Backtester + /simulate + 回本模拟页面
4. EntryScanner + /scan-entry + 扫描页面
5. NextDayPlanner + /next-plan + 计划页面
   关键约束：StrategyEngine 的入参只能包含 t-1 及以前的数据，不得传入当日K线；所有「当日是否成交」的判断只出现在 ExecutionSimulator。
   十四、验收用例（让代码模型据此写测试）
1. 卖出 10000 元：印花税 = 5 元；成交额 1000 元的买入：佣金取最低 5 元。
2. T+1：买入当日不得有任何卖出；次日 sellableQty 等于前一日收盘持仓。
3. 双向挂单：构造 low <= buyLimit 且 high >= sellLimit 的K线，持仓数量不变，已实现收益 = N × (sellLimit - buyLimit) - 费用。
4. 只有一腿成交时，正确登记 pending，超过 maxCarryDays 按 carryPolicy 处理。
5. 所有数量是 100 的整数倍；cash、qty 任何时刻不为负；补仓后持仓市值不超过 totalCapital × maxPositionRatio。
6. 一字板、停牌日无任何成交；挂单价不超出涨跌停价。
7. 无前视：把 t 日及以后的K线全部篡改，planOrders 在 t 日的输出不变。
8. 回本离场：构造一段持续上涨的数据，清仓后 totalPnL >= targetProfitRate × maxInvested。
9. 同一份数据、同一









组参数重复运行结果完全一致（可复现）。
十五、局限与 V2 方向
• 日线无法还原盘中路径，限价单假设能按限价成交且量足够，实际成交率会低于回测。
• 约 200 个交易日样本很少，参数和买点结论容易过拟合；参数调优请只在训练段做，再到后段验证。
• 单边下跌时，补仓会放大亏损，所以必须保留仓位上限、补仓次数上限和止损，不要为了「回本」关掉它们。
• V2：用多只股票的全市场日线训练模型（如 LightGBM），预测次日最高价、最低价相对昨收的偏离，用来替换固定的 kAtr × atrPct 价带；加入分钟线后把撮合升级为分时回放；处理除权除息。