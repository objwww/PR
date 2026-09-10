param(
    [Parameter(Mandatory = $true)][string]$Url,
    [Parameter(Mandatory = $true)][string]$OutFile
)
$ProgressPreference = 'SilentlyContinue'
try {
    $dir = Split-Path -Parent $OutFile
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 60
    $item = Get-Item $OutFile
    Write-Output ('OK ' + $item.Length + 'B ' + $OutFile)
} catch {
    Write-Output ('FAIL ' + $Url + ' :: ' + $_.Exception.Message)
}
