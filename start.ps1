$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$venvPython = Join-Path $projectRoot '.venv\Scripts\python.exe'
$frontendDir = Join-Path $projectRoot 'frontend'
$dataDir = Join-Path $projectRoot '.app-data'
$setupMarker = Join-Path $dataDir 'setup.marker'

if (-not (Test-Path $venvPython)) {
    python -m venv (Join-Path $projectRoot '.venv')
}

New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

$needsPythonSetup = -not (Test-Path $setupMarker)
if (-not $needsPythonSetup) {
    $needsPythonSetup = (Get-Item (Join-Path $projectRoot 'pyproject.toml')).LastWriteTimeUtc -gt (Get-Item $setupMarker).LastWriteTimeUtc
}
if ($needsPythonSetup) {
    & $venvPython -m pip install -e $projectRoot
}

Push-Location $frontendDir
try {
    $needsNodeSetup = -not (Test-Path (Join-Path $frontendDir 'node_modules')) -or -not (Test-Path $setupMarker)
    if (-not $needsNodeSetup) {
        $needsNodeSetup = (Get-Item (Join-Path $frontendDir 'package-lock.json')).LastWriteTimeUtc -gt (Get-Item $setupMarker).LastWriteTimeUtc
    }
    if ($needsNodeSetup) {
        npm install
    }
    npm run build
}
finally {
    Pop-Location
}

Set-Content -LiteralPath $setupMarker -Value (Get-Date -Format o) -Encoding ascii
& $venvPython (Join-Path $projectRoot 'app.py')
