package com.objwww.pr.control.ops.application;

/**
 * expected-revision CAS 竞争败者（M5-11；并发认领恰一人成功的败者面，
 * M5-12 API 映射 409 冲突并携最新投影）。
 */
public class CaseRevisionConflictException extends IllegalStateException {

    public CaseRevisionConflictException(java.util.UUID caseId, long expectedRevision) {
        super("Case revision 冲突: id=" + caseId + " expectedRevision=" + expectedRevision);
    }
}
