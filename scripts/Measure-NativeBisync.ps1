# Controlled Windows host-only old/new bisync no-change measurements.
# PowerShell 7+; entirely disposable local file pairs and temporary rclone state.
# Outputs JSON metrics and native logs. This does not measure Android power use.
param(
    [Parameter(Mandatory)][string]$OldBinary,
    [Parameter(Mandatory)][string]$NewBinary,
    [string]$Evidence = (Join-Path ([IO.Path]::GetTempPath()) ('cloudbridge-benchmark-' + [Guid]::NewGuid().ToString('N'))),
    [int]$Repetitions = 3
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7+ required' }
if ($Repetitions -lt 2 -or $Repetitions -gt 10) { throw 'Repetitions must be 2 to 10' }
$OldBinary = (Resolve-Path -LiteralPath $OldBinary -ErrorAction Stop).Path
$NewBinary = (Resolve-Path -LiteralPath $NewBinary -ErrorAction Stop).Path
$Evidence = [IO.Path]::GetFullPath($Evidence)
if (Test-Path -LiteralPath $Evidence) { throw 'Evidence directory must not exist before benchmark' }
[void](New-Item -ItemType Directory -Path $Evidence -ErrorAction Stop)
$observations = [Collections.Generic.List[object]]::new()
$failures = [Collections.Generic.List[string]]::new()
$children = [Collections.Generic.List[Diagnostics.Process]]::new()

function Run-Rclone([string]$binary,[string[]]$arguments,[int]$timeoutMs) {
    $psi = [Diagnostics.ProcessStartInfo]::new($binary)
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($argument in $arguments) { [void]$psi.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    $clock = [Diagnostics.Stopwatch]::StartNew()
    if (-not $process.Start()) { throw 'rclone subprocess failed to start' }
    $script:children.Add($process)
    # Both streams must drain concurrently in case rclone produces verbose logs.
    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()
    try {
        $maxWorkingSet = [long]0
        while ($true) {
            if ($process.WaitForExit(20)) { break }
            $process.Refresh()
            $maxWorkingSet = [math]::Max($maxWorkingSet,[long]$process.WorkingSet64)
            $maxWorkingSet = [math]::Max($maxWorkingSet,[long]$process.PeakWorkingSet64)
            if ($clock.ElapsedMilliseconds -gt $timeoutMs) {
                $process.Kill($true)
                [void]$process.WaitForExit(5000)
                throw ('Bounded rclone subprocess timed out after ' + $timeoutMs + ' ms')
            }
        }
        $clock.Stop()
        $process.Refresh()
        return [pscustomobject]@{
            ExitCode=[int]$process.ExitCode
            WallMilliseconds=[math]::Round($clock.Elapsed.TotalMilliseconds,3)
            CpuMilliseconds=[math]::Round($process.TotalProcessorTime.TotalMilliseconds,3)
            PeakWorkingSetBytes=$maxWorkingSet
        }
    } finally {
        if (-not $process.HasExited) {
            $process.Kill($true)
            [void]$process.WaitForExit(5000)
        }
        $process.Dispose()
    }
}

function No-Changes([string]$logPath) {
    if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) { return $false }
    $log = [IO.File]::ReadAllText($logPath)
    return ($log -match 'Bisync successful') -and
        ($log -notmatch '(?i)Copied \(new\)|Copied \(replaced|Deleted|Renamed|copying .* to .*')
}

function Run-Case([int]$fileCount,[string]$identity,[string]$binary) {
    $case = Join-Path $Evidence ('files-' + $fileCount + '-' + $identity)
    $left = Join-Path $case 'left'
    $right = Join-Path $case 'right'
    $state = Join-Path $case 'state'
    foreach ($dir in @($case,$left,$right,$state)) {
        [void](New-Item -ItemType Directory -Path $dir -ErrorAction Stop)
    }
    $bytes = [byte[]]::new(256)
    $fixedModtime = [DateTime]::SpecifyKind(([DateTime]::new(2024,1,2,3,4,5)),[DateTimeKind]::Utc)
    for ($j=0; $j -lt $bytes.Length; $j++) { $bytes[$j] = [byte](($j * 17 + 31) % 256) }
    for ($i=0; $i -lt $fileCount; $i++) {
        $name = 'sample-{0:d5}.dat' -f $i
        $leftFile = Join-Path $left $name
        $rightFile = Join-Path $right $name
        [IO.File]::WriteAllBytes($leftFile,$bytes)
        [IO.File]::WriteAllBytes($rightFile,$bytes)
        [IO.File]::SetLastWriteTimeUtc($leftFile,$fixedModtime)
        [IO.File]::SetLastWriteTimeUtc($rightFile,$fixedModtime)
    }
    if (@(Get-ChildItem -LiteralPath $left -File).Count -ne $fileCount) {
        throw 'Disposable dataset creation failed'
    }
    $args = @('bisync',$left,$right,'--workdir',$state,'--config',(Join-Path $case 'missing-config.conf'),'-v')
    $initialLog = Join-Path $case 'init.log'
    $initial = Run-Rclone $binary ($args + @('--resync','--resync-mode=path1','--log-file',$initialLog)) 120000
    if ($initial.ExitCode -ne 0) {
        $failures.Add(('Initialisation failed for ' + $identity + '/' + $fileCount + ': ' + $initial.ExitCode))
        return
    }
    for ($run=1; $run -le $Repetitions; $run++) {
        $log = Join-Path $case ('no-change-' + $run + '.log')
        $sample = Run-Rclone $binary ($args + @('--log-file',$log)) 90000
        $valid = $sample.ExitCode -eq 0 -and (No-Changes $log)
        $observations.Add([pscustomobject]@{
            Binary=$identity; FileCount=$fileCount; Repetition=$run
            NoChangesVerified=$valid; ExitCode=$sample.ExitCode
            WallMilliseconds=$sample.WallMilliseconds
            CpuMilliseconds=$sample.CpuMilliseconds
            PeakWorkingSetBytes=$sample.PeakWorkingSetBytes
            Log=$log
        })
        if (-not $valid) {
            $failures.Add(('No-change validation failed: ' + $identity + '/' + $fileCount + ' run ' + $run))
        }
    }
}

try {
    # Alternate implementation order to reduce, but not eliminate, warm-up bias.
    foreach ($count in @(100,1000)) {
        $order = if ($count -eq 100) { @('old','new') } else { @('new','old') }
        foreach ($identity in $order) {
            $binary = if ($identity -eq 'old') { $OldBinary } else { $NewBinary }
            Run-Case $count $identity $binary
        }
    }
} finally {
    foreach ($child in $children) {
        try {
            if (-not $child.HasExited) {
                $child.Kill($true)
                [void]$child.WaitForExit(5000)
            }
        } catch {}
    }
    $observations | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Evidence 'observations.json') -Encoding utf8
    $summaries = [Collections.Generic.List[object]]::new()
    foreach ($count in @(100,1000)) {
        foreach ($identity in @('old','new')) {
            $subset = @($observations | Where-Object { $_.FileCount -eq $count -and $_.Binary -eq $identity })
            if ($subset.Count -eq 0) { continue }
            $wall = @($subset | ForEach-Object { [double]$_.WallMilliseconds } | Sort-Object)
            $half = [int][math]::Floor($wall.Count / 2)
            $median = if (($wall.Count % 2) -eq 1) { $wall[$half] } else { ($wall[$half-1] + $wall[$half]) / 2.0 }
            $summaries.Add([pscustomobject]@{
                Binary=$identity; Files=$count; Repetitions=$subset.Count
                MedianWallMilliseconds=[math]::Round($median,3)
                MeanCpuMilliseconds=[math]::Round(($subset.CpuMilliseconds | Measure-Object -Average).Average,3)
                MaxPeakWorkingSetBytes=($subset.PeakWorkingSetBytes | Measure-Object -Maximum).Maximum
            })
        }
    }
    $summaries | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Evidence 'summary.json') -Encoding utf8
    $metadata = [pscustomobject]@{
        CreatedUtc=[DateTime]::UtcNow.ToString('o')
        OS=[Runtime.InteropServices.RuntimeInformation]::OSDescription
        Architecture=[Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        LogicalProcessors=[Environment]::ProcessorCount
        OldBinary=$OldBinary; OldSHA256=(Get-FileHash -LiteralPath $OldBinary -Algorithm SHA256).Hash
        NewBinary=$NewBinary; NewSHA256=(Get-FileHash -LiteralPath $NewBinary -Algorithm SHA256).Hash
        Datasets=@(100,1000); Repetitions=$Repetitions
        FileSizeBytes=256; State='Fresh independent bisync state per dataset and implementation'
        Scope='Host local filesystem only; no Android power use, no universal performance conclusions'
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Evidence 'metadata.json') -Encoding utf8
}
Write-Output ('No-change observations: ' + $observations.Count)
Write-Output ('Evidence: ' + $Evidence)
foreach ($failure in $failures) { Write-Output ('FAIL: ' + $failure) }
if ($failures.Count -ne 0) { exit 1 }
Write-Output 'PASS: all no-change host benchmark runs'
