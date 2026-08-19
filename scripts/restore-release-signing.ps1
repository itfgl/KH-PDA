param(
    [Parameter(Mandatory = $true)]
    [string]$EncryptedBundle,

    [string]$PrivateKey = "$env:USERPROFILE\.kaihang-signing-export\recipient-private-key.pem",

    [string]$Certificate = "$env:USERPROFILE\.kaihang-signing-export\recipient-cert.pem"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Resolve-OpenSsl {
    $command = Get-Command openssl -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $gitOpenSsl = 'C:\Program Files\Git\mingw64\bin\openssl.exe'
    if (Test-Path -LiteralPath $gitOpenSsl) {
        return $gitOpenSsl
    }
    throw 'openssl.exe was not found. Install Git for Windows or OpenSSL.'
}

function Escape-JavaPropertyValue([string]$Value) {
    return $Value.Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n').Replace("`t", '\t')
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$encryptedPath = [System.IO.Path]::GetFullPath($EncryptedBundle)
$privateKeyPath = [System.IO.Path]::GetFullPath($PrivateKey)
$certificatePath = [System.IO.Path]::GetFullPath($Certificate)
$keystorePath = Join-Path $repoRoot 'android\app\release.keystore'
$propertiesPath = Join-Path $repoRoot 'keystore.properties'
$temporaryBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$temporaryRoot = [System.IO.Path]::GetFullPath((Join-Path $temporaryBase ("kaihang-signing-" + [guid]::NewGuid().ToString('N'))))
$zipPath = Join-Path $temporaryRoot 'signing.zip'
$plainPath = Join-Path $temporaryRoot 'plain'

if (-not $temporaryRoot.StartsWith($temporaryBase, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Temporary directory is outside the system temp directory: $temporaryRoot"
}

foreach ($requiredPath in @($encryptedPath, $privateKeyPath, $certificatePath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required file does not exist: $requiredPath"
    }
}

New-Item -ItemType Directory -Force -Path $plainPath | Out-Null

try {
    $openssl = Resolve-OpenSsl
    & $openssl cms -decrypt -binary -inform DER `
        -in $encryptedPath `
        -recip $certificatePath `
        -inkey $privateKeyPath `
        -out $zipPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $zipPath)) {
        throw 'Signing bundle decryption failed. Verify that the bundle, certificate, and private key match.'
    }

    Expand-Archive -LiteralPath $zipPath -DestinationPath $plainPath -Force
    $sourceKeystore = Join-Path $plainPath 'release.keystore'
    $sourceSecrets = Join-Path $plainPath 'signing-secrets.json'
    if (-not (Test-Path -LiteralPath $sourceKeystore) -or -not (Test-Path -LiteralPath $sourceSecrets)) {
        throw 'The decrypted bundle is missing release.keystore or signing-secrets.json.'
    }

    $secrets = Get-Content -LiteralPath $sourceSecrets -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($property in @('storePassword', 'keyAlias', 'keyPassword')) {
        if ([string]::IsNullOrWhiteSpace([string]$secrets.$property)) {
            throw "The signing bundle is missing $property"
        }
    }

    Copy-Item -LiteralPath $sourceKeystore -Destination $keystorePath -Force
    $propertyLines = @(
        'storeFile=app/release.keystore'
        ('storePassword=' + (Escape-JavaPropertyValue ([string]$secrets.storePassword)))
        ('keyAlias=' + (Escape-JavaPropertyValue ([string]$secrets.keyAlias)))
        ('keyPassword=' + (Escape-JavaPropertyValue ([string]$secrets.keyPassword)))
    )
    [System.IO.File]::WriteAllText(
        $propertiesPath,
        ($propertyLines -join "`n") + "`n",
        [System.Text.UTF8Encoding]::new($false)
    )

    Write-Output 'Release signing files restored:'
    Write-Output "  $keystorePath"
    Write-Output "  $propertiesPath"
    Write-Output 'Both files are excluded by .gitignore. Run .\gradlew.bat assembleRelease in the android directory.'
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
        if (-not $resolvedTemporaryRoot.StartsWith($temporaryBase, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing unsafe temporary cleanup target: $resolvedTemporaryRoot"
        }
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}
