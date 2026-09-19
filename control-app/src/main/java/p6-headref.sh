#!/bin/sh
git grep -l -E "EvalComparisonAutoRecorder|ReportWritingRubric|RootCauseZhDictionary|ModelFailureGuide|ApprovalQueueFollowUp|MutationToolCatalog|ToolDescriptionZh|RootCauseCatalogPort|ArenaChaosDrillRecovery|CompositeDrillRecovery|DrillRecoveryPort|FlagdDrillRecovery|SixElementsChecker" HEAD -- "control-app/src/main/java" 2>&1
