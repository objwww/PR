param(
    [Parameter(Mandatory = $true)][string]$Url,
    [string]$Pattern = '<img[^>]+?src="([^"]+)"',
    [switch]$AbsoluteOnly
)
$ProgressPreference = 'SilentlyContinue'
try {
    $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 40
    $content = $resp.Content
    $matchesList = [regex]::Matches($content, $Pattern)
    $seen = @{}
    foreach ($m in $matchesList) {
        $src = $m.Groups[1].Value
        if ($src -match '^data:') { continue }
        if ($src -match '^https?://') {
            $abs = $src
        } elseif ($src -match '^//') {
            $abs = 'https:' + $src
        } else {
            $abs = (New-Object System.Uri((New-Object System.Uri($Url)), $src)).AbsoluteUri
        }
        if ($AbsoluteOnly -and $abs -notmatch '\.(png|jpe?g|gif|webp)(\?|$)') { continue }
        if (-not $seen.ContainsKey($abs)) {
            $seen[$abs] = $true
            Write-Output $abs
        }
    }
    if ($seen.Count -eq 0) { Write-Output 'NO_IMAGES_FOUND' }
} catch {
    Write-Output ('ERROR: ' + $_.Exception.Message)
}
