param(
    [ValidateSet('adaptive_30min', 'guardlocal_30min', 'guardcombined_30min', 'horizon_1deg')]
    [string]$Method = 'guardlocal_30min'
)

# Runs one method only. The Java driver owns timing, reference checks and output.
# Memory is sampled for the whole invocation, including load/warmup/output/retained repeats.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaPath = 'D:\Develop\jdk-17.0.20.101-hotspot\bin\java.exe'
$configPath = Join-Path $projectRoot "configs\qinling_512_$Method.properties"
$classPath = Join-Path $projectRoot 'user_runs\.classes'
foreach ($required in @($javaPath, $configPath, (Join-Path $classPath 'ProjectedBaselineExperiment.class'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file missing: $required"
    }
}
$runName = 'qinling_512_{0}_{1}' -f $Method, (Get-Date -Format 'yyyyMMdd_HHmmss_fff')
$outputPath = Join-Path $projectRoot "user_runs\$runName"
$stdoutPath = "$outputPath.stdout.log"
$stderrPath = "$outputPath.stderr.log"
$metricsPath = "$outputPath.process.json"
foreach ($target in @($outputPath, $stdoutPath, $stderrPath, $metricsPath)) {
    if (Test-Path -LiteralPath $target) { throw "Output already exists: $target" }
}
# Quote absolute paths for Windows Start-Process argument serialization.
$javaArguments = '-Xms256m -Xmx2g -cp "{0}" ProjectedBaselineExperiment "{1}" "{2}"' -f $classPath, $configPath, $outputPath
$timer = [System.Diagnostics.Stopwatch]::StartNew()
$process = Start-Process -FilePath $javaPath -ArgumentList $javaArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
# Retain the process handle so exit information remains available after termination.
$null = $process.Handle
$peakWorkingSet = [long]0
$samples = 0
$lastNotice = 0.0
Write-Host "RUN_STARTED method=$Method pid=$($process.Id) output=$outputPath"
while (-not $process.HasExited) {
    try {
        $process.Refresh()
        if (-not $process.HasExited) {
            $peakWorkingSet = [Math]::Max($peakWorkingSet, $process.WorkingSet64)
            $samples++
        }
    } catch {
        if (-not $process.HasExited) { throw }
    }
    if ($timer.Elapsed.TotalSeconds - $lastNotice -ge 30) {
        Write-Host ('RUNNING elapsed_sec={0:F0} sampled_peak_MiB={1:F1}' -f $timer.Elapsed.TotalSeconds, ($peakWorkingSet / 1MB))
        $lastNotice = $timer.Elapsed.TotalSeconds
    }
    Start-Sleep -Milliseconds 500
}
$process.WaitForExit()
$timer.Stop()
$exitCode = $process.ExitCode
$consoleText = Get-Content -LiteralPath $stdoutPath -Raw
$summaryPath = Join-Path $outputPath 'summary.csv'
$complete = ($exitCode -eq 0) -and (Test-Path -LiteralPath $summaryPath) -and ($consoleText -match 'EXPERIMENT_COMPLETE summary=')
[ordered]@{
    method = $Method
    process_id = $process.Id
    exit_code = $exitCode
    experiment_complete = $complete
    output = $outputPath
    wall_sec = $timer.Elapsed.TotalSeconds
    sample_interval_ms = 500
    memory_samples = $samples
    sampled_process_peak_working_set_bytes = $peakWorkingSet
    memory_scope = 'Whole JVM invocation: input, reference, warmup, three repeats, retained results, output. Sampled maximum, not exact peak or algorithm-only memory.'
    timing_scope = 'Use summary.csv runtime_sec for median algorithm compute time. This wall_sec also includes loading, warmup, output and monitoring wait.'
} | ConvertTo-Json | Set-Content -LiteralPath $metricsPath -Encoding UTF8
Write-Host $consoleText
Get-Content -LiteralPath $stderrPath | ForEach-Object { Write-Host $_ }
Write-Host "PROCESS_RECORD=$metricsPath"
if (-not $complete) { throw "Experiment incomplete; exit_code=$exitCode. Preserve output and report both logs." }
Write-Host "RUN_512_METHOD_COMPLETE summary=$summaryPath"
