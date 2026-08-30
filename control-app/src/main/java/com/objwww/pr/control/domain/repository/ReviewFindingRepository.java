package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.ReviewFinding;

import java.util.List;
import java.util.UUID;

public interface ReviewFindingRepository {

    void insert(ReviewFinding finding);

    List<ReviewFinding> findByRunId(UUID reviewRunId);
}
