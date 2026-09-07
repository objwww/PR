package com.objwww.pr.control.ops.application;

import java.util.UUID;

/** Case 不存在（M5-11；M5-12 API 映射 404）。 */
public class CaseNotFoundException extends IllegalStateException {

    public CaseNotFoundException(UUID caseId) {
        super("Case 不存在: id=" + caseId);
    }
}
