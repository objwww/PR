package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.RunStep;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunStepRepository {

    void save(RunStep step);

    Optional<RunStep> findById(UUID id);

    List<RunStep> findByRunId(UUID reviewRunId);
}
