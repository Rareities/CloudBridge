param(
    [string]$ApkDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'app/build/outputs/apk/oss/debug'),
    [string]$SdkDirectory = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$ExpectedEngineCommit,
    [string]$ExpectedCertificateSha256,
    [switch]$RequireRelease,
    [string]$ReportPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'app/build/reports/verified-apks.json')
)
$ErrorActionPreference = 'Stop'
if (-not $ExpectedEngineCommit) {
    $properties = Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot -Parent) 'gradle.properties')
    $ExpectedEngineCommit = ($properties | Where-Object { $_ -match '^io\.github\.rareities\.protonbridge\.rCloneRef=' }).Split('=')[1]
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
    if ($RequireRelease) {
        if (!$ExpectedCertificateSha256) { throw 'An approved release certificate is required' }
        $aapt = Join-Path $SdkDirectory "build-tools/$BuildToolsVersion/aapt2.exe"
        if (!(Test-Path -LiteralPath $aapt)) { $aapt = Join-Path $SdkDirectory "build-tools/$BuildToolsVersion/aapt2" }
        $badging = @(& $aapt dump badging $apk.FullName)
        if ($LASTEXITCODE -ne 0 -or $badging -match '^application-debuggable') { throw 'Release APK is invalid or debuggable' }
        if (!($badging -match "^package: name='io\.github\.rareities\.protonbridge'")) { throw 'Unexpected release application identity' }
    }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try {
        $entry = $zip.GetEntry('assets/engine-provenance.json')
        if (-not $entry -or $entry.Length -gt 65536) { throw "Missing or invalid provenance in $($apk.Name)" }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $provenance = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($provenance.commit -ne $ExpectedEngineCommit -or $provenance.repository -ne 'https://github.com/Rareities/rclone.git') {
            throw "Unexpected engine source in $($apk.Name)"
        }
        if ($provenance.cgoEnabled -ne $true -or $provenance.goos -ne 'android' -or
            $provenance.buildMode -ne 'pie' -or $provenance.androidApi -ne 23 -or
            $provenance.maxPageSize -ne 16384 -or $provenance.buildTags -notcontains 'noselfupdate') {
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
                $header = [byte[]]::new(64)
                $used = 0
                while ($used -lt $header.Length) {
                    $read = $stream.Read($header, $used, $header.Length - $used)
                    if ($read -eq 0) { throw 'Truncated ELF header' }
                    $used += $read
                }
                $machine = [int]$header[18] + 256 * [int]$header[19]
                if ($header[0] -ne 127 -or [System.Text.Encoding]::ASCII.GetString($header,1,3) -ne 'ELF' -or
                    $header[5] -ne 1 -or $header[4] -ne $architectures[$abi][0] -or $machine -ne $architectures[$abi][1] -or
                    [BitConverter]::ToUInt16($header,16) -ne 3) {
                    throw "ELF architecture mismatch: $abi"
                }
                $is64 = $header[4] -eq 2
                $phoff = if($is64) { [BitConverter]::ToUInt64($header,32) } else { [BitConverter]::ToUInt32($header,28) }
                $phsize = [BitConverter]::ToUInt16($header, $(if($is64){54}else{42}))
                $phcount = [BitConverter]::ToUInt16($header, $(if($is64){56}else{44}))
                if ($phoff -lt $(if($is64){64}else{52}) -or $phoff -gt 65536 -or $phcount -lt 1 -or $phcount -gt 128 -or
                    $phsize -ne $(if($is64){56}else{32})) { throw "Invalid ELF program header table: $abi" }
                $stream.Dispose()
                $stream = $library.Open()
                $remaining = [int]$phoff
                while($remaining -gt 0) { if($stream.ReadByte() -lt 0){throw 'Truncated ELF'}; $remaining-- }
                $loads = 0
                $interpreter = $false
                $interpreterOffset = 0
                $interpreterSize = 0
                for($p=0; $p -lt $phcount; $p++) {
                    $program = [byte[]]::new($phsize)
                    $used = 0
                    while($used -lt $phsize) {
                        $read = $stream.Read($program,$used,$phsize-$used)
                        if($read -eq 0){throw 'Truncated program header'}
                        $used += $read
                    }
                    $type = [BitConverter]::ToUInt32($program,0)
                    if($type -eq 1) {
                        $align = if($is64){[BitConverter]::ToUInt64($program,48)}else{[BitConverter]::ToUInt32($program,28)}
                        if($align -lt 16384){throw "ELF load alignment does not support 16 KiB pages: $abi"}
                        $loads++
                    }
                    if($type -eq 3){
                        if($interpreter){throw 'Multiple ELF interpreters'}
                        $interpreter=$true
                        $interpreterOffset = if($is64){[BitConverter]::ToUInt64($program,8)}else{[BitConverter]::ToUInt32($program,4)}
                        $interpreterSize = if($is64){[BitConverter]::ToUInt64($program,32)}else{[BitConverter]::ToUInt32($program,16)}
                    }
                }
                if($loads -lt 1 -or !$interpreter){throw "Expected dynamically linked Android PIE: $abi"}
                if($interpreterOffset -gt 1048576 -or $interpreterSize -lt 2 -or $interpreterSize -gt 256){throw 'Invalid ELF interpreter'}
                $stream.Dispose()
                $stream = $library.Open()
                for($skip=0; $skip -lt $interpreterOffset; $skip++){if($stream.ReadByte() -lt 0){throw 'Truncated ELF interpreter'}}
                $pathBytes = [byte[]]::new($interpreterSize)
                for($n=0; $n -lt $pathBytes.Length; $n++){
                    $next=$stream.ReadByte(); if($next -lt 0){throw 'Truncated ELF interpreter'}; $pathBytes[$n]=$next
                }
                $interpreterPath = [Text.Encoding]::ASCII.GetString($pathBytes).TrimEnd([char]0)
                if($interpreterPath -ne $(if($is64){'/system/bin/linker64'}else{'/system/bin/linker'})){
                    throw "The native interpreter is not Android bionic: $abi"
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
        androidPieVerified = $true; elfLoadAlignment16KiBVerified = $true
    }
})
New-Item -ItemType Directory -Force -Path (Split-Path $ReportPath -Parent) | Out-Null
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
$results | Select-Object file,bytes,@{Name='ABIs';Expression={$_.abis -join ','}},sha256 | Format-Table -AutoSize
Write-Output "Verified $($results.Count) APKs. Report: $ReportPath"
