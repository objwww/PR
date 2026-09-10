param(
    [Parameter(Mandatory = $true)][string]$Url,
    [Parameter(Mandatory = $true)][string]$Pattern
)
$ProgressPreference = 'SilentlyContinue'
try {
    $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 40
    $found = [regex]::Matches($resp.Content, $Pattern) |
        ForEach-Object { if ($_.Groups.Count -gt 1) { $_.Groups[1].Value } else { $_.Value } } |
        Sort-Object -Unique
    if ($found) { $found } else { Write-Output 'NO_MATCH' }
} catch {
    Write-Output ('ERROR: ' + $_.Exception.Message)
}
