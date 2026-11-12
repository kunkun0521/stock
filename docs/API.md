基于以下表的数据，如何利用遗传算法做一个股票看涨看跌的功能，预测未来每只股票1天，3天，5天，10天，15天的，基于历史数据，通过不断的进化。淘汰，
并且把淘汰的股票记录下来。能够查看哪些历史预测正确，预测不准确的可视化看板，方便回顾。给我一个需求设计文档，采用什么模型，公式，可以新建表，但
给出表结构的字段描述，表干嘛的。其次，可以结合业内的专业的模型，以及以下表的历史数据，自行新增功能，功能需要描述清楚，怎么使用。给我一份需求文档，可以直接把文档给AI工具，
AI就能根据需求文档编码实现。
create table stock_daily
(
code        varchar(10)                           not null comment '股票代码，不含交易所前缀',
market      char(2)                               not null comment '交易所：SH=上海，SZ=深圳',
trade_date  date                                  not null comment '交易日期',
open        decimal(12, 4)                        null comment '开盘价，元；复权口径见adjustflag',
close       decimal(12, 4)                        null comment '收盘价，元；复权口径见adjustflag',
high        decimal(12, 4)                        null comment '最高价，元；复权口径见adjustflag',
low         decimal(12, 4)                        null comment '最低价，元；复权口径见adjustflag',
preclose    decimal(12, 4)                        null comment '前收盘价，元；复权口径见adjustflag',
volume      decimal(20, 2)                        null comment '成交量，股',
amount      decimal(24, 2)                        null comment '成交额，元',
amplitude   decimal(10, 4)                        null comment '振幅百分比，(最高价-最低价)/前收盘价×100',
pct_chg     decimal(10, 4)                        null comment '涨跌幅百分比',
chg_amt     decimal(12, 4)                        null comment '涨跌额，元',
turnover    decimal(10, 4)                        null comment '换手率百分比',
adjustflag  tinyint     default 2                 not null comment '价格复权标记：1=后复权，2=前复权，3=不复权',
tradestatus tinyint                               null comment '交易状态：1=交易，0=停牌',
is_st       tinyint                               null comment '是否ST：1=是，0=否',
pe_ttm      decimal(20, 6)                        null comment '滚动市盈率（TTM）',
pb_mrq      decimal(20, 6)                        null comment '市净率（MRQ）',
ps_ttm      decimal(20, 6)                        null comment '滚动市销率（TTM）',
pcf_ncf_ttm decimal(20, 6)                        null comment '滚动市现率（TTM，经营现金流口径）',
data_source varchar(32) default 'baostock'        not null comment '数据来源标识',
created_at  datetime    default CURRENT_TIMESTAMP not null comment '记录首次写入时间',
updated_at  datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '记录最近更新时间',
primary key (code, market, trade_date, adjustflag)
);
create table stock_score_daily
(
code            varchar(10)                        not null comment '股票代码',
market          char(2)                            not null comment '市场代码(SH/SZ/BJ)',
trade_date      date                               not null comment '交易日期',
adjustflag      tinyint  default 2                 not null comment '复权标志(1前复权 2不复权 3后复权)',
total_score     decimal(10, 4)                     null comment '综合评分',
value_score     decimal(10, 4)                     null comment '价值因子评分',
momentum_score  decimal(10, 4)                     null comment '动量因子评分',
trend_score     decimal(10, 4)                     null comment '趋势因子评分',
risk_score      decimal(10, 4)                     null comment '风险因子评分',
liquidity_score decimal(10, 4)                     null comment '流动性因子评分',
close           decimal(12, 4)                     null comment '收盘价',
pe_ttm          decimal(20, 6)                     null comment '市盈率(TTM)',
pb_mrq          decimal(20, 6)                     null comment '市净率(MRQ)',
ps_ttm          decimal(20, 6)                     null comment '市销率(TTM)',
pcf_ncf_ttm     decimal(20, 6)                     null comment '市现率(经营现金流TTM)',
ret_21          decimal(12, 6)                     null comment '21日收益率',
ret_63          decimal(12, 6)                     null comment '63日收益率',
ret_126         decimal(12, 6)                     null comment '126日收益率',
ret_252         decimal(12, 6)                     null comment '252日收益率',
vol_60          decimal(12, 6)                     null comment '60日波动率',
drawdown_252    decimal(12, 6)                     null comment '252日最大回撤',
amount_ratio_20 decimal(12, 6)                     null comment '20日成交额变化比率',
turnover_20     decimal(12, 6)                     null comment '20日平均换手率',
created_at      datetime default CURRENT_TIMESTAMP not null comment '创建时间',
updated_at      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
primary key (code, market, trade_date, adjustflag)
);