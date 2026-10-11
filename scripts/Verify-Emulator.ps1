# Requires PowerShell 7+. Use a disposable API 35 emulator with no personal accounts.
# The instrumentation creates local aliases and replaces the DEBUG app's test config.
# It never logs into Proton or installs over the release application.
param(
    [Parameter(Mandatory)][string] $Adb,
    [Parameter(Mandatory)][string] $Serial,
    [Parameter(Mandatory)][string] $AppApk,
    [Parameter(Mandatory)][string] $TestApk,
    [Parameter(Mandatory)][switch] $DisposableEmulator,
    [string] $OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) ('protonbridge-device-' + [guid]::NewGuid().ToString('N')))
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }
if (!$DisposableEmulator -or $Serial -notmatch '^emulator-[0-9]+$') { throw 'An explicitly acknowledged disposable emulator is required.' }
$Adb = (Get-Command -Name $Adb -CommandType Application -ErrorAction Stop).Source
$AppApk = (Resolve-Path -LiteralPath $AppApk).Path
$TestApk = (Resolve-Path -LiteralPath $TestApk).Path
if ((Test-Path -LiteralPath $OutputDirectory) -and @(Get-ChildItem -LiteralPath $OutputDirectory -Force).Count) {
    throw 'Choose a new, empty evidence directory.'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$results = [Collections.Generic.List[object]]::new()
$package = 'io.github.rareities.protonbridge.debug'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"

function Invoke-Adb([string[]] $Arguments, [int] $TimeoutSeconds = 45) {
    $start = [Diagnostics.ProcessStartInfo]::new($Adb)
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($value in @('-s', $Serial) + $Arguments) { [void]$start.ArgumentList.Add($value) }
    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            [void]$process.WaitForExit(10000)
            throw "ADB operation exceeded $TimeoutSeconds seconds. Inspect the disposable emulator."
        }
        $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "ADB exited $($process.ExitCode): $text" }
        return $text
    } finally { $process.Dispose() }
}

function Run-Case([string] $Name, [string] $Class, [int] $Expected, [string[]] $Extra = @()) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $output = Invoke-Adb (@('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Class) + $Extra + @($runner)) 300
    [IO.File]::WriteAllText((Join-Path $OutputDirectory "$Name.log"), $output)
    $passed = $output -match "OK \($Expected tests?\)" -and $output -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED'
    $results.Add([pscustomobject]@{name=$Name; passed=$passed; expectedTests=$Expected; seconds=$timer.Elapsed.TotalSeconds})
    if (!$passed) { throw "Device test $Name did not pass. Read $Name.log; ADB exit zero is not a test pass." }
    Write-Output "PASS $Name ($Expected test cases)"
}

function Export-EditorImage([string] $Name) {
    $start = [Diagnostics.ProcessStartInfo]::new($Adb)
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($value in @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', "cache/final-qa-$Name.png")) {
        [void]$start.ArgumentList.Add($value)
    }
    $destination = Join-Path $OutputDirectory "editor-$Name.png"
    $process = [Diagnostics.Process]::Start($start)
    $file = [IO.File]::Create($destination)
    try {
        $copy = $process.StandardOutput.BaseStream.CopyToAsync($file)
        $errorText = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(30000)) { $process.Kill($true); throw 'Screenshot export timed out' }
        [void]$copy.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw ('Screenshot export failed: ' + $errorText.GetAwaiter().GetResult()) }
    } finally { $file.Dispose(); $process.Dispose() }
    $header = [IO.File]::ReadAllBytes($destination)
    if ($header.Length -lt 8 -or [Convert]::ToHexString($header[0..7]) -ne '89504E470D0A1A0A') {
        throw 'Screenshot export did not contain a PNG image'
    }
}

try {
    $hardware = (Invoke-Adb @('shell', 'getprop', 'ro.hardware')).Trim()
    $api = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
    if ($hardware -notmatch 'ranchu|goldfish' -or $api -ne '35') { throw 'This acceptance suite targets a disposable API 35 emulator only.' }
    [pscustomobject]@{
        serial=$Serial; hardware=$hardware; api=$api; startedUtc=[DateTime]::UtcNow.ToString('o')
        appSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $AppApk).Hash.ToLowerInvariant()
        testsSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $TestApk).Hash.ToLowerInvariant()
        liveProton=$false; physicalDevice=$false
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'environment.json')
    Invoke-Adb @('install', '-r', $AppApk) 120 | Write-Output
    Invoke-Adb @('install', '-r', $TestApk) 120 | Write-Output
    [void](Invoke-Adb @('shell', 'pm', 'grant', $package, 'android.permission.POST_NOTIFICATIONS'))
    [void](Invoke-Adb @('shell', 'appops', 'set', $package, 'MANAGE_EXTERNAL_STORAGE', 'allow'))
    Run-Case 'native-workflow' 'io.github.rareities.protonbridge.BisyncDeviceIntegrationTest' 3
    Run-Case 'schedule-stage' 'io.github.rareities.protonbridge.ScheduledRestartIntegrationTest#stageForRestart' 1
    [void](Invoke-Adb @('shell', 'am', 'force-stop', $package))
    Run-Case 'schedule-restart' 'io.github.rareities.protonbridge.ScheduledRestartIntegrationTest#verifyRestartRunsOneRealScheduledBisync' 1
    Run-Case 'rc-lifecycle' 'io.github.rareities.protonbridge.RcDeviceIntegrationTest' 1
    Run-Case 'provider-workflow' 'io.github.rareities.protonbridge.ProviderDeviceIntegrationTest' 1
    Run-Case 'ordinary-copy-sync' 'io.github.rareities.protonbridge.OrdinaryTaskDeviceIntegrationTest' 1
    Run-Case 'remote-configuration' 'io.github.rareities.protonbridge.RemoteConfigurationDeviceTest' 1
    Run-Case 'constraints-cancellation' 'io.github.rareities.protonbridge.SystemJobIntegrationTest' 1
    Run-Case 'editor-native-workflow' 'io.github.rareities.protonbridge.TaskEditorDeviceIntegrationTest' 1
    foreach ($name in @('bisync-ready', 'advanced-options', 'schedule-and-actions')) { Export-EditorImage $name }
    Run-Case 'restore-directory-durability' 'io.github.rareities.protonbridge.RestoreDirectoryDurabilityTest' 1
    Run-Case 'encrypted-crash-stage' 'io.github.rareities.protonbridge.EncryptedCrashRecoveryTest' 1 @('-e', 'recoveryPhase', 'stage')
    [void](Invoke-Adb @('shell', 'am', 'force-stop', $package))
    Run-Case 'encrypted-crash-restart' 'io.github.rareities.protonbridge.EncryptedCrashRecoveryTest' 1 @('-e', 'recoveryPhase', 'verify')
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'native-processes.txt'), (Invoke-Adb @('shell', 'ps', '-A')))
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'app-memory.txt'), (Invoke-Adb @('shell', 'dumpsys', 'meminfo', $package)))
} finally {
    # Restore only the virtual battery test setting, not user/app data.
    try { [void](Invoke-Adb @('shell', 'dumpsys', 'battery', 'reset')) } catch { Write-Warning $_ }
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'results.json')
}
Write-Output "PASS: all explicit device phases. Evidence: $OutputDirectory"
