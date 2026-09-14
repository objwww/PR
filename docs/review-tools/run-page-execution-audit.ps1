$ErrorActionPreference = 'Stop'
$reviewRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location -LiteralPath $reviewRoot
try {
    $reviewReport = [xml](Get-Content control-app/target/surefire-reports/TEST-com.objwww.pr.control.eval.application.EvalBatchRunnerTest.xml -Raw)
    $reviewCp = ($reviewReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $reviewJava = ($reviewReport.testsuite.properties.property | Where-Object name -eq 'java.home').value
    New-Item -ItemType Directory -Force docs/review-tools/build | Out-Null
    & "$reviewJava/bin/javac.exe" -cp $reviewCp -d docs/review-tools/build docs/review-tools/PageExecutionAudit.java
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    & "$reviewJava/bin/java.exe" -cp "docs/review-tools/build;$reviewCp" PageExecutionAudit
    if ($LASTEXITCODE -ne 0) { throw 'Java page execution probe failed' }
} finally { Pop-Location }
