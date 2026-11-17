param(
    [Parameter(Mandatory=$true)][string]$Classes,
    [long]$ApprovedQueryCap = 4692052
)
$ErrorActionPreference = 'Stop'
if ($ApprovedQueryCap -lt 4692052) {
    throw 'Paired protocol needs explicit approval for 4692052 new queries; required protocol cap is 4692052. No experiment started.'
}
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $OutputEncoding
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$java = 'D:\Develop\jdk-17.0.20.101-hotspot\bin\java.exe'
$classesPath = (Resolve-Path -LiteralPath $Classes).Path
if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw "java not found: $java" }
if (!(Test-Path -LiteralPath (Join-Path $classesPath 'QinlingTimeSearchPairedBenchmark.class') -PathType Leaf)) {
    throw 'Selected directory has no QinlingTimeSearchPairedBenchmark.class'
}
$protection = Join-Path $root 'user_runs\qinling_time_search_paired_preparation_20261110_001\protected_before.csv'
$protected = @(Import-Csv -LiteralPath $protection)
foreach ($file in $protected) {
    if ((Get-FileHash -LiteralPath $file.Path -Algorithm SHA256).Hash -ne $file.Hash) {
        throw "Protected source/input changed; inspect before running: $($file.Path)"
    }
}
$tag = (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + [guid]::NewGuid().ToString('N').Substring(0,8)
$output = Join-Path $root ('user_runs\qinling_time_search_paired_' + $tag)
if (Test-Path -LiteralPath $output) { throw "Output exists: $output" }
$protected | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath ($output + '.protected_before.csv')
$config = Join-Path $root 'configs\qinling_time_search_paired.properties'
$ErrorActionPreference = 'Continue'
& $java '-Dfile.encoding=UTF-8' -Xms64m -Xmx512m -cp $classesPath QinlingTimeSearchPairedBenchmark --run $config $output $ApprovedQueryCap 2>&1 |
    Tee-Object -FilePath ($output + '.console.log')
$result = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$after = @($protected | ForEach-Object { Get-FileHash -LiteralPath $_.Path -Algorithm SHA256 } | Select-Object Path,Hash)
$after | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath ($output + '.protected_after.csv')
$changes = @(Compare-Object $protected $after -Property Path,Hash)
Write-Output "COMPARISON_OUTPUT $output"
if ($changes.Count -ne 0) { throw 'Protected files changed; stop and review the snapshots' }
if ($result -ne 0) { throw "Record checks failed (exit $result); retain output and console log" }
Write-Output 'QINLING_TIME_SEARCH_PAIRED_COMPARISON_COMPLETE (both methods newly computed; 3 formal pairs)'
