package com.objwww.pr.control.ops.domain.repository;

import java.util.List;

/**
 * 分区目录（M5-18 读面：pg_inherits 投影）。返回指定父表的分区名清单
 * （含 default 分区——候选判定由 RetentionService 排除，目录只如实上报）。
 */
public interface PartitionCatalog {

    List<String> partitionsOf(String tableName);
}
