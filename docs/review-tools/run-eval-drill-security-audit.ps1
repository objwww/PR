$ErrorActionPreference = 'Stop'
$auditRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location -LiteralPath $auditRoot
try {
    $auditReport = [xml](Get-Content control-app/target/surefire-reports/TEST-com.objwww.pr.control.eval.application.EvalBatchRunnerTest.xml -Raw)
    $auditCp = ($auditReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $auditJava = ($auditReport.testsuite.properties.property | Where-Object name -eq 'java.home').value
    New-Item -ItemType Directory -Force docs/review-tools/build | Out-Null
    & "$auditJava/bin/javac.exe" -cp $auditCp -d docs/review-tools/build docs/review-tools/EvalDrillSecurityAudit.java
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    & "$auditJava/bin/java.exe" -cp "docs/review-tools/build;$auditCp" EvalDrillSecurityAudit
    if ($LASTEXITCODE -ne 0) { throw 'audit failed' }
} finally { Pop-Location }
