param(
    [string]$ApkDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'app/build/outputs/apk/oss/debug'),
    [string]$SdkDirectory = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$ExpectedEngineCommit,
    [string]$ExpectedCertificateSha256,
    [string]$ReportPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'app/build/reports/verified-apks.json')
)
$ErrorActionPreference = 'Stop'
if (-not $ExpectedEngineCommit) {
    $properties = Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot -Parent) 'gradle.properties')
    $ExpectedEngineCommit = ($properties | Where-Object { $_ -match '^de\.schuelken\.cloudbridge\.rCloneRef=' }).Split('=')[1]
}
if ($ExpectedEngineCommit -notmatch '^[0-9a-f]{40}$') { throw 'An immutable expected engine SHA is required' }
if (-not $SdkDirectory) { throw 'Set ANDROID_HOME or pass -SdkDirectory' }
$signer = Join-Path $SdkDirectory "build-tools/$BuildToolsVersion/apksigner.bat"
if (-not (Test-Path -LiteralPath $signer)) { $signer = Join-Path $SdkDirectory "build-tools/$BuildToolsVersion/apksigner" }
if (-not (Test-Path -LiteralPath $signer)) { throw 'Android apksigner was not found' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$architectures = @{ 'armeabi-v7a' = @(1,40); 'arm64-v8a' = @(2,183); 'x86' = @(1,3); 'x86_64' = @(2,62) }
$apks = @(Get-ChildItem -LiteralPath $ApkDirectory -Filter '*.apk' -File)
if ($apks.Count -eq 0) { throw 'No APKs found' }
$results = @(foreach ($apk in $apks) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try {
        $entry = $zip.GetEntry('assets/engine-provenance.json')
        if (-not $entry -or $entry.Length -gt 65536) { throw "Missing or invalid provenance in $($apk.Name)" }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $provenance = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($provenance.commit -ne $ExpectedEngineCommit -or $provenance.repository -ne 'https://github.com/Rareities/rclone.git') {
            throw "Unexpected engine source in $($apk.Name)"
        }
        if ($provenance.cgoEnabled -ne $false -or $provenance.buildTags -notcontains 'noselfupdate') {
            throw 'Unexpected native build policy'
        }
        $libraries = @($zip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/librclone\.so$' })
        if ($libraries.Count -notin @(1,4)) { throw "Unexpected native library count in $($apk.Name)" }
        $abis = @(foreach ($library in $libraries) {
            $abi = $library.FullName.Split('/')[1]
            if (-not $architectures.ContainsKey($abi)) { throw "Unsupported ABI: $abi" }
            $record = @($provenance.artifacts | Where-Object { $_.abi -eq $abi })
            if ($record.Count -ne 1 -or [long]$record[0].bytes -ne $library.Length) { throw "Size/provenance mismatch: $abi" }
            $stream = $library.Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $digest = ($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '' }
            finally { $stream.Dispose(); $sha.Dispose() }
            if ($digest -ne $record[0].sha256) { throw "Packaged engine hash mismatch: $abi" }
            $stream = $library.Open()
            try {
                $header = [byte[]]::new(20)
                $used = 0
                while ($used -lt $header.Length) {
                    $read = $stream.Read($header, $used, $header.Length - $used)
                    if ($read -eq 0) { throw 'Truncated ELF header' }
                    $used += $read
                }
                $machine = [int]$header[18] + 256 * [int]$header[19]
                if ($header[0] -ne 127 -or [System.Text.Encoding]::ASCII.GetString($header,1,3) -ne 'ELF' -or
                    $header[5] -ne 1 -or $header[4] -ne $architectures[$abi][0] -or $machine -ne $architectures[$abi][1]) {
                    throw "ELF architecture mismatch: $abi"
                }
            } finally { $stream.Dispose() }
            $abi
        })
    } finally { $zip.Dispose() }
    $certificate = @(& $signer verify --verbose --print-certs $apk.FullName)
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $($apk.Name)" }
    $line = $certificate | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
    if (-not $line) { throw 'Certificate fingerprint not reported' }
    $fingerprint = ($line -replace '^Signer #1 certificate SHA-256 digest:\s*','').Trim().ToLowerInvariant()
    if ($ExpectedCertificateSha256 -and $fingerprint -ne $ExpectedCertificateSha256.Replace(':','').ToLowerInvariant()) {
        throw 'Signing certificate mismatch'
    }
    [pscustomobject]@{
        file = $apk.Name; bytes = $apk.Length; sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        abis = $abis; engineCommit = $ExpectedEngineCommit; signingCertificateSha256 = $fingerprint
        signatureVerified = $true; engineHashesVerified = $true; elfArchitecturesVerified = $true
    }
})
New-Item -ItemType Directory -Force -Path (Split-Path $ReportPath -Parent) | Out-Null
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
$results | Select-Object file,bytes,@{Name='ABIs';Expression={$_.abis -join ','}},sha256 | Format-Table -AutoSize
Write-Output "Verified $($results.Count) APKs. Report: $ReportPath"
