$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$featureId = 'com.ditrix.edt.mcp.server.feature.feature.group'
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryPath = Join-Path $projectRoot 'mcp\repositories\com.ditrix.edt.mcp.server.repository\target\repository'

function Find-EdtExecutable {
    if ($env:EDT_EXE) {
        if (-not (Test-Path -LiteralPath $env:EDT_EXE -PathType Leaf)) {
            throw "EDT_EXE does not point to a file: $env:EDT_EXE"
        }

        $resolvedExecutable = (Resolve-Path -LiteralPath $env:EDT_EXE).Path
        if ([System.IO.Path]::GetFileName($resolvedExecutable) -ne '1cedt.exe') {
            throw "EDT_EXE must point to 1cedt.exe, not: $resolvedExecutable"
        }

        return $resolvedExecutable
    }

    $searchRoots = @(
        (Join-Path $env:LOCALAPPDATA '1C\1cedtstart\installations'),
        (Join-Path $env:ProgramFiles '1C\1CE\components')
    )

    if (${env:ProgramFiles(x86)}) {
        $searchRoots += Join-Path ${env:ProgramFiles(x86)} '1C\1CE\components'
    }

    $candidates = foreach ($searchRoot in $searchRoots) {
        if (Test-Path -LiteralPath $searchRoot -PathType Container) {
            Get-ChildItem -LiteralPath $searchRoot -Recurse -File -Filter '1cedt.exe' -ErrorAction SilentlyContinue
        }
    }

    $selected = $candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $selected) {
        throw '1C:EDT is not installed. Install EDT in EDT Start, or set EDT_EXE to the full path of 1cedt.exe.'
    }

    return $selected.FullName
}

function Find-EdtJavaExecutable {
    if ($env:EDT_JAVA) {
        if (-not (Test-Path -LiteralPath $env:EDT_JAVA -PathType Leaf)) {
            throw "EDT_JAVA does not point to a file: $env:EDT_JAVA"
        }

        return (Resolve-Path -LiteralPath $env:EDT_JAVA).Path
    }

    $componentsRoot = Join-Path $env:ProgramFiles '1C\1CE\components'
    $selected = Get-ChildItem -LiteralPath $componentsRoot -Directory -Filter 'axiom-jdk-full-*' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        ForEach-Object { Get-Item -LiteralPath (Join-Path $_.FullName 'bin\javaw.exe') -ErrorAction SilentlyContinue } |
        Select-Object -First 1

    if (-not $selected) {
        throw 'Axiom Java for 1C:EDT was not found. Set EDT_JAVA to the full path of javaw.exe.'
    }

    return $selected.FullName
}

function Assert-EdtJava17 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JavaExecutable
    )

    if ([System.IO.Path]::GetFileName($JavaExecutable) -ne 'javaw.exe') {
        throw "EDT_JAVA must point to javaw.exe, not: $JavaExecutable"
    }

    $javaHome = Split-Path -Parent (Split-Path -Parent $JavaExecutable)
    $releaseFile = Join-Path $javaHome 'release'
    if (-not (Test-Path -LiteralPath $releaseFile -PathType Leaf)) {
        throw "Java release metadata was not found: $releaseFile"
    }

    $releaseMetadata = Get-Content -LiteralPath $releaseFile -Raw
    if ($releaseMetadata -notmatch '(?m)^JAVA_VERSION="17(?:\.|\")') {
        throw "1C:EDT debug requires Java 17: $JavaExecutable"
    }
}

function Test-McpHealth {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url
    )

    try {
        $request = [System.Net.WebRequest]::Create($Url)
        $request.Proxy = $null
        $request.Timeout = 2000
        $response = $request.GetResponse()
        try {
            return [int]$response.StatusCode -eq 200
        }
        finally {
            $response.Close()
        }
    }
    catch {
        return $false
    }
}

$edtExecutable = Find-EdtExecutable
$edtJavaExecutable = Find-EdtJavaExecutable
Assert-EdtJava17 -JavaExecutable $edtJavaExecutable
Write-Host "1C:EDT: $edtExecutable"
Write-Host "EDT Java: $edtJavaExecutable"

if (-not (Test-Path -LiteralPath $repositoryPath -PathType Container)) {
    throw "Local p2 repository is missing after build: $repositoryPath"
}

$runningEdt = Get-Process -Name '1cedt' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $edtExecutable }
if ($runningEdt) {
    throw '1C:EDT is already running. Close it before installing the local MCP build.'
}

$repositoryUri = 'file:///' + ($repositoryPath -replace '\\', '/')
$edtDirectory = Split-Path -Parent $edtExecutable
$bundlesInfo = Join-Path $edtDirectory 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
$replaceInstalledFeature = (Test-Path -LiteralPath $bundlesInfo -PathType Leaf) -and
    [bool](Select-String -LiteralPath $bundlesInfo -SimpleMatch 'com.ditrix.edt.mcp.server,' -Quiet)
$provisioningAction = if ($replaceInstalledFeature) {
    "-uninstallIU $featureId -installIU $featureId"
}
else {
    "-installIU $featureId"
}
$directorArguments = '-vm "{0}" -nosplash -application org.eclipse.equinox.p2.director -repository "{1}" {2} -profileProperties org.eclipse.update.reconcile=true' -f $edtJavaExecutable, $repositoryUri, $provisioningAction

Write-Host "Provisioning $featureId from $repositoryUri"
$director = Start-Process -FilePath $edtExecutable -ArgumentList $directorArguments -WindowStyle Hidden -Wait -PassThru
if ($director.ExitCode -ne 0) {
    throw "p2 director failed with exit code $($director.ExitCode)."
}

$workspacePath = $env:EDT_WORKSPACE
if (-not $workspacePath) {
    $workspacePath = Join-Path $env:LOCALAPPDATA '1C\EDT-MCP\workspace'
}
New-Item -ItemType Directory -Path $workspacePath -Force | Out-Null

$port = 8765
if ($env:EDT_MCP_PORT) {
    if (-not [int]::TryParse($env:EDT_MCP_PORT, [ref]$port)) {
        throw "EDT_MCP_PORT is not a valid integer: $env:EDT_MCP_PORT"
    }
}

$env:EDT_MCP_AUTO_START = 'true'
$env:EDT_MCP_PORT = [string]$port

$launchArguments = '-data "{0}" -clean -vm "{1}" -vmargs -DnativeFormBufferedLayoutRender=true' -f $workspacePath, $edtJavaExecutable

Write-Host "Launching 1C:EDT with workspace $workspacePath"
$edtProcess = Start-Process -FilePath $edtExecutable -ArgumentList $launchArguments -PassThru
$healthUrl = "http://127.0.0.1:$port/health"
$deadline = [DateTime]::UtcNow.AddSeconds(90)

do {
    Start-Sleep -Seconds 1

    if ($edtProcess.HasExited) {
        throw "1C:EDT exited before MCP became ready. Exit code: $($edtProcess.ExitCode)."
    }

    if (Test-McpHealth -Url $healthUrl) {
        Write-Host "EDT MCP is ready: $healthUrl"
        exit 0
    }
}
while ([DateTime]::UtcNow -lt $deadline)

throw "1C:EDT is running, but MCP did not become ready within 90 seconds: $healthUrl"
