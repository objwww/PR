-- V39 — B-21 缺陷修复：operator_command.state 拓宽 varchar(16)→varchar(32)
--
--   症状（195 官方 verify 当场抓住，ExA2LeaseCancelFenceIT.cancelVsFinishRaceIsConsistent）：
--   Cancel vs finish 竞态落到"finish 先胜"分支时，取消命令被裁决为
--   REJECTED_FORBIDDEN（18 字符）——V27 列宽 varchar(16) 装不下，写路径 22001
--   value too long 崩溃。此前定时运气全落 REJECTED_STALE（14 字符）故从未触发；
--   生产语义不变量：任何 State 枚举值必须可落库（列宽 ≥ 最长枚举名）。
--
--   修复：拓宽到 varchar(32)（最长枚举 18 字符 + 余量）；只动列类型，
--   授权/默认值/约束原样（alter column type 不触 grant 与 default 语义）。
--
--   回滚：alter table operator_command alter column state type varchar(16);
--         （仅当全表无 REJECTED_FORBIDDEN 行时安全）
--   编号纪律：rebase 时以下一可用号为准替换 V39。

alter table operator_command
    alter column state type varchar(32);
