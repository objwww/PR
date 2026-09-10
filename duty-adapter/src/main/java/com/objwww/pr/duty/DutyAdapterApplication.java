package com.objwww.pr.duty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 127 独立故障域值班适配器（AM7 M7-17；INV-AM5-4 外部腿的接收面）。
 *
 * <p>两腿故障边界：195 全挂时本服务用最后已知排班快照照发 webhook（通知面不断），
 * 台账回写落本地 spool 事后补；本服务/Gatus 挂=外部腿盲区（§8 已登记的残余风险，
 * 靠 restart=always + 快照通道 fallback 的 env 独立性缓解）。
 */
@SpringBootApplication
@EnableScheduling
public class DutyAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(DutyAdapterApplication.class, args);
    }
}
