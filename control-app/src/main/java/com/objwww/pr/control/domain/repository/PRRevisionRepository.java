package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.shared.RevisionFingerprint;

import java.util.Optional;
import java.util.UUID;

/**
 * PRRevision 端口。只插不更（I12：同 fingerprint 复用行，不覆盖）；
 * update/delete 由 DB trigger 拒绝（I9）。
 */
public interface PRRevisionRepository {

    void insert(PRRevision revision);

    Optional<PRRevision> findById(UUID id);

    /** 对齐 uq_pr_revision_fingerprint(pr_subject_id, revision_fingerprint) */
    Optional<PRRevision> findByFingerprint(UUID prSubjectId, RevisionFingerprint fingerprint);
}
