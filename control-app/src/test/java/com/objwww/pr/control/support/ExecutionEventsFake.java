package com.objwww.pr.control.support;

import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.shared.ExecutionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 执行事件账本的内存 fake（M3 模型治理测试装备）。
 * AM1-T00 清障后自旧 InMemoryStores.Events 提取为独立类（原 PR 大 fake 已随死代码删除）。
 */
public final class ExecutionEventsFake implements ExecutionEventRepository {
    private final List<ExecutionEvent> all = new ArrayList<>();

    @Override
    public void append(ExecutionEvent event) {
        all.add(event);
    }

    @Override
    public List<ExecutionEvent> findByRunIdOrdered(UUID reviewRunId) {
        return all.stream().filter(e -> e.reviewRunId().equals(reviewRunId)).toList();
    }

    public List<ExecutionEvent> all() {
        return List.copyOf(all);
    }
}
