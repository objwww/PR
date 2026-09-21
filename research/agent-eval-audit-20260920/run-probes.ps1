$ErrorActionPreference = 'Stop'
$auditRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$auditXmlPath = Join-Path $auditRoot 'control-app/target/surefire-reports/TEST-com.objwww.pr.control.alert.application.mcp.McpToolInvokerTest.xml'
if (-not (Test-Path -LiteralPath $auditXmlPath)) {
    throw 'Run the Maven test command in REPORT.md first to generate compiled classes and the test classpath.'
}
[xml]$auditXml = Get-Content -LiteralPath $auditXmlPath -Raw
$auditClassPath = ($auditXml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
$auditClasses = Join-Path $auditRoot 'control-app/target/agent-eval-audit-20260920'
New-Item -ItemType Directory -Path $auditClasses -Force | Out-Null
& javac -encoding UTF-8 -cp $auditClassPath -d $auditClasses (Join-Path $PSScriptRoot 'AgentEvalAuditProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Audit probe compilation failed.' }
& java -cp "$auditClasses;$auditClassPath" com.objwww.pr.control.alert.application.mcp.AgentEvalAuditProbe
if ($LASTEXITCODE -ne 0) { throw 'Audit characterization changed or failed; inspect output.' }
