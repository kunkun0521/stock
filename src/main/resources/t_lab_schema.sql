-- ============================================================
-- 做T回本 / 补仓 / 买点扫描（t-lab 独立模块）建表脚本
-- 规格第十节。独立文件，不加入 application.yaml 的 schema-locations，
-- 因此不会影响原有项目启动流程；未执行本脚本时接口照常返回结果，
-- 只是不落库并在 warnings 中给出提示。
-- 执行方式：mysql -uroot -p stock_eastmoney < t_lab_schema.sql
-- ============================================================

create table if not exists t_sim_task (
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

create table if not exists t_sim_daily (
  task_id bigint not null, trade_date date not null,
  close decimal(12,4), qty int, sellable_qty int, cash decimal(16,2),
  equity decimal(16,2), diluted_cost decimal(12,4), total_pnl decimal(16,2),
  events varchar(64) comment '当日事件标记: ADD,T,EXIT,STOP',
  primary key (task_id, trade_date)
);

create table if not exists t_sim_trade (
  id bigint auto_increment primary key,
  task_id bigint not null, trade_date date not null,
  action varchar(12) not null comment 'INIT/ADD/T_SELL/T_BUY/BUYBACK/SELL_PENDING/EXIT/STOP',
  price decimal(12,4), qty int, amount decimal(16,2), fee decimal(12,2),
  realized_pnl decimal(16,2) null, note varchar(255) null,
  index idx_trade_task (task_id, trade_date)
);

create table if not exists t_scan_result (
  task_id bigint not null, buy_date date not null,
  final_pnl_pct decimal(10,4), buy_hold_pct decimal(10,4), only_add_pct decimal(10,4),
  max_drawdown decimal(10,4), recover_days int null, t_count int, t_net_profit decimal(16,2),
  features_json json,
  primary key (task_id, buy_date)
);
