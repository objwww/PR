package com.objwww.pr.control.ops.domain.repository;

/**
 * 冷层存储（M5-19；外部副作用替身点）。冷层形态（本地盘卷/对象存储）= 开放项
 * O-5——端口先行，实现随部署段冻结。
 */
public interface ColdArchiveStore {

    /** 导出内容落冷层，返回可回读引用（export_ref） */
    String export(String partition, byte[] content);

    /** 回读（条数/digest 校验的证据面；不可达/损坏以异常或内容差异显形） */
    byte[] readBack(String ref);
}
