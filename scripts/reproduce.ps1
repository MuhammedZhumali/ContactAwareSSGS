param(
    [string]$JavaCommand = 'java',
    [string]$OutputDirectory = 'target/reproduction'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $jar = Join-Path $projectRoot 'target/contact-aware-contacts.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Build first: .\mvnw.cmd verify' }
    $outputRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
    if (Test-Path -LiteralPath $outputRoot) { throw "Choose a new output directory: $outputRoot" }
    $baseline = Join-Path $projectRoot 'data/runs/iss-20261010'
    function Invoke-Contacts {
        param([string[]]$Arguments)
        & $JavaCommand -jar $jar @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Contact command failed: $($Arguments[0])" }
    }
    Invoke-Contacts -Arguments @('satnogs-replay','--dataset',"$baseline/satnogs/dataset.json",'--out',"$outputRoot/satnogs")
    $common = @('orekit','--norad','25544','--start','2026-10-10T00:00:00Z','--end','2026-10-11T00:00:00Z',
        '--orbit',"$projectRoot/data/sources/celestrak-iss-20261011/gp.json",
        '--stations',"$baseline/satnogs/stations.json",'--orekit-data',"$projectRoot/data/sources/earth-20261011",'--min-elevation-deg','10')
    Invoke-Contacts -Arguments ($common + @('--out',"$outputRoot/orekit"))
    Invoke-Contacts -Arguments @('compare','--satnogs',"$outputRoot/satnogs/dataset.json",'--orekit',"$outputRoot/orekit/dataset.json",'--out',"$outputRoot/comparison",'--short-seconds','300')
    Invoke-Contacts -Arguments @('schedule-select','--dataset',"$outputRoot/orekit/dataset.json",'--switch-guard-seconds','0','--out',"$outputRoot/schedule")
    Invoke-Contacts -Arguments @('analyze-gaps','--dataset',"$outputRoot/schedule/schedule.json",'--short-seconds','300','--out',"$outputRoot/schedule/analysis")
    Invoke-Contacts -Arguments @('analyze-gaps','--dataset',"$outputRoot/orekit/dataset.json",'--short-seconds','300','--out',"$outputRoot/schedule/candidate-analysis")
    $checks = @()
    $artifacts = @('satnogs/contacts.json','orekit/contacts.json','comparison/comparison.json','comparison/comparison.csv',
        'schedule/selected-contacts.json','schedule/schedule.json','schedule/selection-report.json')
    foreach ($analysisDirectory in @('analysis','candidate-analysis')) {
        foreach ($filename in @('gap-analysis.json','gap-analysis.csv','same-gs-recurrence.csv')) {
            $artifacts += "schedule/$analysisDirectory/$filename"
        }
    }
    foreach ($relative in $artifacts) {
        $expected = (Get-FileHash -LiteralPath "$baseline/$relative" -Algorithm SHA256).Hash
        $actual = (Get-FileHash -LiteralPath "$outputRoot/$relative" -Algorithm SHA256).Hash
        if ($expected -ne $actual) { throw "Reproduction differs: $relative" }
        $checks += [ordered]@{ file=$relative; sha256=$actual.ToLowerInvariant(); equal=$true }
    }
    # Numerical convergence check for the entire 32-station cohort, not only a unit-test station.
    Invoke-Contacts -Arguments ($common + @('--max-check-seconds','7','--out',"$outputRoot/orekit-fine"))
    $coarse = @(Get-Content -LiteralPath "$outputRoot/orekit/contacts.json" -Raw | ConvertFrom-Json)
    $fine = @(Get-Content -LiteralPath "$outputRoot/orekit-fine/contacts.json" -Raw | ConvertFrom-Json)
    if ($coarse.Count -ne $fine.Count) { throw 'Finer event check changed contact count' }
    $fineById = @{}
    foreach ($contact in $fine) { $fineById[$contact.id] = $contact }
    $maxDelta = 0.0
    foreach ($contact in $coarse) {
        $other = $fineById[$contact.id]
        if ($null -eq $other) { throw "Missing contact with fine step: $($contact.id)" }
        foreach ($boundary in @('start','end')) {
            $delta = [Math]::Abs(([DateTimeOffset]$contact.$boundary - [DateTimeOffset]$other.$boundary).TotalSeconds)
            $maxDelta = [Math]::Max($maxDelta,$delta)
        }
    }
    if ($maxDelta -gt 0.002) { throw "Event boundaries did not converge: $maxDelta seconds" }
    $report = [ordered]@{offline=$true; exactReproduction=$checks; convergence=[ordered]@{stations=32;contacts=$coarse.Count;checkSeconds=@(30,7);maxBoundaryDeltaSeconds=$maxDelta;toleranceSeconds=0.002}}
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$outputRoot/verification.json" -Encoding UTF8
    Write-Output "Verified offline reproduction and numerical convergence: $outputRoot/verification.json"
} finally { Pop-Location }
