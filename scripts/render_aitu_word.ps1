param([string]$Iteration='v1')
$ErrorActionPreference='Stop'
$revisionRoot=Split-Path $PSScriptRoot -Parent
$renderDirectory=Join-Path $revisionRoot "target\aitu-revision\document-qa\$Iteration"
New-Item -ItemType Directory -Path $renderDirectory -Force | Out-Null
$wordQA=New-Object -ComObject Word.Application
$wordQA.Visible=$false
$wordQA.DisplayAlerts=0
$renderRecords=@()
try {
 foreach($artifactStem in @('article','supplement')) {
  $documentPath=Join-Path $revisionRoot "docs\aitu-revision\$artifactStem.docx"
  $pdfPath=Join-Path $renderDirectory "$artifactStem.pdf"
  $wordDocument=$wordQA.Documents.Open($documentPath,$false,$true)
  try {
   $wordDocument.Fields.Update() | Out-Null
   $wordDocument.Repaginate()
   $wordDocument.ExportAsFixedFormat($pdfPath,17)
   $renderRecords+=@{name=$artifactStem;pages=$wordDocument.ComputeStatistics(2);words=$wordDocument.ComputeStatistics(0);nativeEquationCount=$wordDocument.OMaths.Count;renderer=$wordQA.Version;pdf=$pdfPath}
  } finally {$wordDocument.Close(0)}
 }
 $renderRecords | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $renderDirectory 'render.json') -Encoding utf8
 $renderRecords | ConvertTo-Json -Depth 5
} finally {$wordQA.Quit()}
