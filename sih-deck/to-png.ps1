$ErrorActionPreference = "Stop"
$src = "C:\Users\prabhat\OneDrive\Desktop\codingbase\Projects\ItantraV2\sih-deck\VANI-SIH2026-v2.pptx"
$out = "C:\Users\prabhat\OneDrive\Desktop\codingbase\Projects\ItantraV2\sih-deck\render"
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $pp = New-Object -ComObject PowerPoint.Application
    $pres = $pp.Presentations.Open($src, $true, $false, $false)
    $pres.Export($out, "PNG", 1600, 900)
    $pres.Close()
    $pp.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($pp) | Out-Null
    Write-Output "PNG_OK"
} catch {
    Write-Output ("PNG_FAIL: " + $_.Exception.Message)
}
