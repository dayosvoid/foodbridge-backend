$envFile = Join-Path $PSScriptRoot ".env"

if (-not (Test-Path $envFile)) {
    throw ".env file not found: $envFile"
}

Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()

    if ($line -and -not $line.StartsWith("#")) {
        $parts = $line -split "=", 2

        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable(
                $parts[0].Trim(),
                $parts[1].Trim(),
                "Process"
            )
        }
    }
}

Write-Host "Environment variables loaded successfully."
