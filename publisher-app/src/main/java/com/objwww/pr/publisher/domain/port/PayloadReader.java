package com.objwww.pr.publisher.domain.port;

import com.objwww.pr.shared.Digest;

import java.util.Map;

/**
 * 命令 payload 读取端口：payload 正文在 CAS（命令表只存 digest/hash，§5 大对象边界）。
 * Publisher 以只读挂载共享 Control 的 CAS 目录；实现同时负责 JSON 解析，
 * 返回已解析的字段表供 gate 校验与 Handler 翻译（domain 内不做 JSON 解析）。
 */
public interface PayloadReader {

    /**
     * @param payloadHash outbox_command.payload_hash（M0 与 CAS digest 同源）
     * @throws PayloadUnavailableException CAS 中不存在或无法解析（fail-closed，E5）
     */
    Map<String, Object> read(Digest payloadHash);
}
