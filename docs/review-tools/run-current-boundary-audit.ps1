# Run the targeted Maven tests in the report first to refresh classes and classpaths.
$ErrorActionPreference = 'Stop'
$reviewRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location -LiteralPath $reviewRoot
try {
    $controlReport = [xml](Get-Content control-app/target/surefire-reports/TEST-com.objwww.pr.control.alert.application.agent.PrimaryClaimAdmissionTest.xml -Raw)
    $notifyReport = [xml](Get-Content notify-app/target/surefire-reports/TEST-com.objwww.pr.notify.domain.service.FencedNotifyExecutorTest.xml -Raw)
    $reviewCp = ($controlReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value + ';' + ($notifyReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $reviewJava = ($controlReport.testsuite.properties.property | Where-Object name -eq 'java.home').value
    New-Item -ItemType Directory -Force docs/review-tools/build | Out-Null
    & "$reviewJava/bin/javac.exe" -cp $reviewCp -d docs/review-tools/build docs/review-tools/CurrentBoundaryAudit.java docs/review-tools/InFlightExitAudit.java
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    & "$reviewJava/bin/java.exe" -cp "docs/review-tools/build;$reviewCp" CurrentBoundaryAudit
    if ($LASTEXITCODE -ne 0) { throw 'Java boundary probe failed' }
    & "$reviewJava/bin/java.exe" -cp "docs/review-tools/build;$reviewCp" InFlightExitAudit
    if ($LASTEXITCODE -ne 0) { throw 'In-flight probe failed' }
    node docs/review-tools/current-frontend-audit.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Frontend probe failed' }
    python docs/review-tools/verify-render-attributes.py
    if ($LASTEXITCODE -ne 0) { throw 'HTML attribute probe failed' }
} finally {
    Pop-Location
}
