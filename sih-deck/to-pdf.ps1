$ErrorActionPreference = "Stop"
$src = "C:\Users\prabhat\OneDrive\Desktop\codingbase\Projects\ItantraV2\sih-deck\VANI-SIH2026-v2.pptx"
$dst = "C:\Users\prabhat\OneDrive\Desktop\codingbase\Projects\ItantraV2\sih-deck\VANI-SIH2026-v2.pdf"
try {
    $pp = New-Object -ComObject PowerPoint.Application
    $pres = $pp.Presentations.Open($src, $true, $false, $false)
    $pres.SaveAs($dst, 32)
    $n = $pres.Slides.Count
    $pres.Close()
    $pp.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($pp) | Out-Null
    Write-Output "PDF_OK slides=$n"
} catch {
    Write-Output ("PDF_FAIL: " + $_.Exception.Message)
}
