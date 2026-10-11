# Disposable loopback-only Rclone HTTP/WebDAV authentication and TLS checks.
# Run with PowerShell 7+: ./scripts/Verify-NativeListeners.ps1 -Binary <host-rclone>
param(
    [Parameter(Mandatory)][string] $Binary,
    [string] $Evidence = (Join-Path ([IO.Path]::GetTempPath()) ('cloudbridge-native-security-' + [Guid]::NewGuid().ToString('N')))
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7+ required' }
$Binary = (Resolve-Path -LiteralPath $Binary -ErrorAction Stop).Path
$Evidence = [IO.Path]::GetFullPath($Evidence)
if (Test-Path -LiteralPath $Evidence) { throw 'Evidence directory already exists' }
[void](New-Item -ItemType Directory -Path $Evidence -ErrorAction Stop)
$results = [Collections.Generic.List[object]]::new()
$failures = [Collections.Generic.List[string]]::new()
$children = [Collections.Generic.List[Diagnostics.Process]]::new()
$authUser = 'loopback-' + [Guid]::NewGuid().ToString('N')
$authPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$rsa = $null
$certificate = $null

function Record([string]$mode,[string]$name,[int]$status,[bool]$passed,[string]$note='') {
    $script:results.Add([pscustomobject]@{Mode=$mode;Test=$name;Status=$status;Pass=$passed;Note=$note})
    if (-not $passed) { $script:failures.Add("$mode | $name (HTTP $status): $note") }
}

function Start-Native([string[]]$arguments,[string]$user,[string]$password) {
    $psi = [Diagnostics.ProcessStartInfo]::new($script:Binary)
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $false
    $psi.RedirectStandardError = $true
    foreach ($argument in $arguments) { [void]$psi.ArgumentList.Add($argument) }
    # Deliberately omit --user/--pass from arguments.
    $psi.Environment['RCLONE_USER'] = $user
    $psi.Environment['RCLONE_PASS'] = $password
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw 'Failed to launch native listener' }
    $script:children.Add($process)
    return $process
}

function Stop-Native($process) {
    if ($null -eq $process) { return }
    try {
        if (-not $process.HasExited) {
            $process.Kill($true)
            if (-not $process.WaitForExit(5000)) { throw 'Child did not exit within five seconds of kill' }
        }
    } finally { $process.Dispose() }
}

function Pick-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start()
    try { return ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Wait-Ready($child,[int]$port) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.ElapsedMilliseconds -lt 10000) {
        if ($child.HasExited) {
            $errorText = $child.StandardError.ReadToEnd().Replace($script:authPassword,'[redacted]')
            throw ('Server exited during startup: ' + $child.ExitCode + '; ' + $errorText)
        }
        $socket = [Net.Sockets.TcpClient]::new()
        try {
            $connection = $socket.ConnectAsync([Net.IPAddress]::Loopback,$port)
            if ($connection.Wait(150) -and $socket.Connected) { return }
        } catch {} finally { $socket.Dispose() }
        Start-Sleep -Milliseconds 75
    }
    throw 'Server did not bind to localhost within ten seconds'
}

function Request([string]$scheme,[int]$port,[string]$method,[string]$path,[bool]$useAuth,[bool]$tls) {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $handler.AllowAutoRedirect = $false
    if ($tls) {
        # Trust is limited to this one client which always targets 127.0.0.1
        # and uses our just-created disposable certificate. No user store edits.
        $handler.ServerCertificateCustomValidationCallback = [Net.Http.HttpClientHandler]::DangerousAcceptAnyServerCertificateValidator
    }
    $client = [Net.Http.HttpClient]::new($handler,$true)
    $client.Timeout = [TimeSpan]::FromSeconds(4)
    $uri = [Uri]::new(($scheme + '://127.0.0.1:' + $port + $path))
    $req = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($method),$uri)
    if ($useAuth) {
        $pair = [Text.Encoding]::UTF8.GetBytes(($script:authUser + ':' + $script:authPassword))
        $req.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',[Convert]::ToBase64String($pair))
    }
    if ($method -eq 'PROPFIND') { [void]$req.Headers.TryAddWithoutValidation('Depth','0') }
    try {
        $response = $client.SendAsync($req).GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return [pscustomobject]@{Status=[int]$response.StatusCode;Body=$body}
        } finally { $response.Dispose() }
    } finally { $req.Dispose(); $client.Dispose() }
}

function Verify-Server([string]$kind,[bool]$tls) {
    $protocol = if ($tls) { 'https' } else { 'http' }
    $mode = $kind + '-' + $protocol
    $folder = Join-Path $Evidence $mode
    [void](New-Item -ItemType Directory -Path $folder)
    $inside = 'TEST-IN-ROOT-' + $kind
    $outside = 'TEST-OUTSIDE-ROOT-' + $kind
    [IO.File]::WriteAllText((Join-Path $folder 'inside.txt'),$inside)
    [IO.File]::WriteAllText((Join-Path $Evidence 'outside.txt'),$outside)
    $log = Join-Path $folder 'server.log'
    $port = Pick-Port
    $args = @('serve',$kind,$folder,('--addr=127.0.0.1:' + $port),'--read-only','--config',(Join-Path $folder 'unused.conf'),'--log-file',$log,'-v')
    if ($tls) { $args += @('--cert',(Join-Path $Evidence 'localhost-cert.pem'),'--key',(Join-Path $Evidence 'localhost-key.pem'),'--min-tls-version=tls1.2') }
    $child = $null
    try {
        $child = Start-Native $args $script:authUser $script:authPassword
        Wait-Ready $child $port
        $get = Request $protocol $port 'GET' '/inside.txt' $false $tls
        Record $mode 'unauthorised GET' $get.Status ($get.Status -in @(401,403)) 'Unauthenticated GET must be rejected'
        $ok = Request $protocol $port 'GET' '/inside.txt' $true $tls
        Record $mode 'authorised GET exact content' $ok.Status ($ok.Status -eq 200 -and $ok.Body -ceq $inside) 'Basic authentication and file bytes'
        if ($tls) {
            $rejected = $false
            try { $null = Request $protocol $port 'GET' '/inside.txt' $true $false }
            catch { $rejected = $true }
            Record $mode 'untrusted TLS certificate rejected' 0 $rejected 'The ordinary client must reject the ephemeral self-signed certificate; the positive fixture above trusts it only in a separate loopback test client'
        }
        $opt = Request $protocol $port 'OPTIONS' '/' $false $tls
        # Capability-only OPTIONS is commonly public for HTTP/WebDAV.
        Record $mode 'unauthorised OPTIONS no private data' $opt.Status (($opt.Status -in @(200,204,207,401,403,405)) -and -not $opt.Body.Contains($inside) -and -not $opt.Body.Contains($outside)) 'No private bytes or filename content exposed through OPTIONS'
        $prop = Request $protocol $port 'PROPFIND' '/' $false $tls
        Record $mode 'unauthorised PROPFIND' $prop.Status ($prop.Status -in @(401,403,405,501)) 'Metadata enumeration must be blocked'
        if ($kind -eq 'webdav') {
            $authProp = Request $protocol $port 'PROPFIND' '/' $true $tls
            Record $mode 'authorised PROPFIND' $authProp.Status ($authProp.Status -eq 207) 'WebDAV multistatus expected'
        }
        foreach ($path in @('/%2e%2e/outside.txt','/%2e%2e%2foutside.txt','/%2e%2e/%2e%2e/outside.txt')) {
            $escape = Request $protocol $port 'GET' $path $true $tls
            Record $mode ('root escape '+$path) $escape.Status ($escape.Status -notin @(200,206,207) -and -not $escape.Body.Contains($outside)) 'Root traversal must not serve outside file'
        }
        Record $mode 'child alive during checks' 0 (-not $child.HasExited) 'Native child unexpectedly terminated'
    } catch {
        $script:failures.Add(($mode + ': ' + $_.Exception.Message))
    } finally {
        Stop-Native $child
        if (Test-Path -LiteralPath $log) {
            $content = $null
            for ($try=0; $try -lt 20; $try++) {
                try {
                    $stream = [IO.File]::Open($log,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
                    try {
                        $reader = [IO.StreamReader]::new($stream)
                        $content = $reader.ReadToEnd()
                        $reader.Dispose()
                    } finally { $stream.Dispose() }
                    break
                } catch [IO.IOException] { Start-Sleep -Milliseconds 100 }
            }
            Record $mode 'logs contain no password' 0 ($null -ne $content -and -not $content.Contains($script:authPassword)) 'Native log must remain readable without exposing password'
        }
    }
}

try {
    # Self-signed test key created in memory, exported only into this disposable
    # folder; no existing certificate store or user key is read.
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $san = [Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
    $san.AddDnsName('localhost')
    $san.AddIpAddress([Net.IPAddress]::Loopback)
    $request.CertificateExtensions.Add($san.Build())
    $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1),[DateTimeOffset]::UtcNow.AddDays(1))
    [IO.File]::WriteAllText((Join-Path $Evidence 'localhost-cert.pem'),$certificate.ExportCertificatePem())
    [IO.File]::WriteAllText((Join-Path $Evidence 'localhost-key.pem'),$rsa.ExportPkcs8PrivateKeyPem())
    foreach ($kind in @('http','webdav')) {
        foreach ($tls in @($false,$true)) { Verify-Server $kind $tls }
    }
} finally {
    foreach ($child in $children) {
        try { if (-not $child.HasExited) { $child.Kill($true); [void]$child.WaitForExit(5000) } } catch {}
    }
    if ($null -ne $certificate) { $certificate.Dispose() }
    if ($null -ne $rsa) { $rsa.Dispose() }
    foreach ($filename in @('localhost-key.pem','localhost-cert.pem')) {
        $path = Join-Path $Evidence $filename
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Evidence 'security.json') -Encoding utf8
    $metadata = [pscustomobject]@{
        TimestampUtc=[DateTime]::UtcNow.ToString('o')
        HostOS=[Runtime.InteropServices.RuntimeInformation]::OSDescription
        Architecture=[Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        Binary=$Binary; BinarySHA256=(Get-FileHash -LiteralPath $Binary -Algorithm SHA256).Hash
        Cases=$results.Count; Failures=$failures.Count; TLS='ephemeral localhost RSA self-signed'
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Evidence 'metadata.json') -Encoding utf8
}
Write-Output ('Listener checks: ' + $results.Count + '; failures: ' + $failures.Count)
Write-Output ('Evidence: ' + $Evidence)
foreach ($failure in $failures) { Write-Output ('FAIL: ' + $failure) }
if ($failures.Count -gt 0) { exit 1 }
Write-Output 'PASS: loopback listener security checks'
