package com.objwww.pr.arenaadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * arena-chaos-admin 进程入口（AM2 C-3 拓扑）：chaos 管理面独立服务——
 * 独立容器、只加入 eval-mgmt 私网、持 CHAOS_ADMIN_TOKEN；oa_chaos_session /
 * oa_chaos_event / ground_truth_scenario / oa_scenario_map 的唯一写者，
 * 业务表零权限（角色 chaos_admin_app，权限矩阵见 arena 迁移 V1/V3）。
 * 业务 order-arena 容器只在 alert-net，对 session 只读——分网不成立的前提下
 * "同容器双网监听"被 C-3 否决，故拆本服务。
 */
@SpringBootApplication
public class ChaosAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChaosAdminApplication.class, args);
    }
}
