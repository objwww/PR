package com.objwww.pr.arena;

/**
 * 有 start/stop 语义的长活组件（流量发生器等），由 ArenaRuntime 统一启停。
 */
public interface StartStoppable {

    void start();

    void stop();
}
