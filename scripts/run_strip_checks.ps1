param([string]$Jdk = 'D:\Develop\jdk-17.0.20.101-hotspot')
$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $OutputEncoding
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
$tag = (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + ([guid]::NewGuid().ToString('N').Substring(0,8))
$output = Join-Path $workspace ('user_runs\strip_checks_' + $tag)
if (Test-Path -LiteralPath $output) { throw "Output already exists: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
# Snapshot pre-existing algorithm/config files. New code and tests are fingerprinted separately.
$protected = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'src'),(Join-Path $workspace 'configs') -File -Recurse |
    Where-Object { $_.Name -notin @('StripUpperBoundVisibility.java','StripVisibilityChecks.java') })
$before = @($protected | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash)
$before | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $output 'protected_before.csv')
$sources = @('AsciiGrid','ZeroPolicy','CoordinateContract','AngleReference','SearchConfig',
    'VisibilityResult','VisibilityStatus','RunStatistics','ControlledVisibility',
    'BlockUpperBoundVisibility','StripUpperBoundVisibility','StripVisibilityChecks') |
    ForEach-Object { Join-Path $workspace ('src\' + $_ + '.java') }
$sources | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } | Select-Object Path,Hash |
    Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $output 'compiled_sources.csv')
New-Item -ItemType File -Path (Join-Path $output 'compile.log') | Out-Null
$ErrorActionPreference = 'Continue' # Windows PowerShell wraps native stderr, including java -version.
& (Join-Path $Jdk 'bin\java.exe') -version 2>&1 | Out-File -Encoding UTF8 -LiteralPath (Join-Path $output 'java_version.log')
if ($LASTEXITCODE -ne 0) { throw 'java -version failed' }
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -Xlint:all -d $classes @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $output 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "Compilation failed; logs retained in $output" }
& (Join-Path $Jdk 'bin\java.exe') '-Dfile.encoding=UTF-8' -Xms64m -Xmx512m -cp $classes StripVisibilityChecks (Join-Path $output 'results') 2>&1 |
    Tee-Object -FilePath (Join-Path $output 'checks.log')
$runExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$after = @($protected | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash)
$after | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $output 'protected_after.csv')
$difference = @(Compare-Object $before $after -Property Path,Hash)
$manifest = [ordered]@{
    scope='synthetic_three_way_correctness_only'; output=$output; run_exit=$runExit;
    protected_files=$before.Count; protected_unchanged=($difference.Count -eq 0);
    old_classes_not_used=$true; performance_measured=$false; novelty_established=$false
}
$manifest | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $output 'manifest.json')
if ($difference.Count -ne 0) { throw 'Protected source/config fingerprints changed' }
if ($runExit -ne 0) { throw "Checks failed; logs retained in $output" }
Write-Output "STRIP_VALIDATION_COMPLETE output=$output protected_unchanged=true"
