param(
    [Parameter(Mandatory = $true)][string]$Url,
    [string]$Needle = ''
)
$ProgressPreference = 'SilentlyContinue'
try {
    $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 40
    $content = $resp.Content
    $found = [regex]::Matches($content, 'https?://[^\s"''<>)]+?\.(?:png|jpe?g|gif|webp)(?:\?[^\s"''<>)]*)?') |
        ForEach-Object { $_.Value } | Sort-Object -Unique
    if ($found) { $found } else { Write-Output 'NO_ABSOLUTE_IMAGE_URLS' }
    # also relative refs like /docs/...png or ../_images
    $rel = [regex]::Matches($content, '(?:src|href|content)="(/[^"]+?\.(?:png|jpe?g|gif|webp))"') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    if ($rel) { Write-Output '--- relative ---'; $rel }
    if ($Needle) {
        Write-Output "--- lines containing '$Needle' ---"
        [regex]::Matches($content, "[^\n]{0,120}$Needle[^\n]{0,120}") |
            ForEach-Object { $_.Value } | Select-Object -First 10
    }
} catch {
    Write-Output ('ERROR: ' + $_.Exception.Message)
}
