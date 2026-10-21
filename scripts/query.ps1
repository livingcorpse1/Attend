<#
.SYNOPSIS
    Runs a read only query against the configured database.

.DESCRIPTION
    Reads the connection settings from the .env file, opens a JDBC connection and runs
    the statement that is given. Only statements that read are allowed, so the script
    can be used to look at the data without changing it. Nothing secret is printed.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\query.ps1 "select count(*) from app_user"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Statement,
    [string]$EnvironmentFile = '.env'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    Write-Host "No $EnvironmentFile found." -ForegroundColor Yellow
    exit 2
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $index = $trimmed.IndexOf('=')
    if ($index -lt 1) { continue }
    $key = $trimmed.Substring(0, $index).Trim()
    $value = $trimmed.Substring($index + 1).Trim().Trim('"').Trim("'")
    $settings[$key] = $value
}

$url = $settings['DATABASE_URL']
$user = $settings['DATABASE_USER']
$password = $settings['DATABASE_PASSWORD']

if (-not $url) {
    Write-Host 'DATABASE_URL is missing from the environment file.' -ForegroundColor Red
    exit 2
}

$trimmedStatement = $Statement.Trim()
if ($trimmedStatement -notmatch '^(select|with)\s') {
    Write-Host 'Only a statement that reads is allowed.' -ForegroundColor Red
    exit 2
}

$driver = Get-ChildItem "$env:USERPROFILE\.m2\repository\org\postgresql\postgresql" -Recurse -Filter 'postgresql-*.jar' `
    -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $driver) {
    Write-Host 'The PostgreSQL driver is not in the local Maven repository yet.' -ForegroundColor Yellow
    exit 3
}

$probe = Join-Path ([System.IO.Path]::GetTempPath()) ("veriqra-query-" + [guid]::NewGuid().ToString('N') + '.java')
$source = @'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

public class Query {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2]);
             Statement statement = connection.createStatement()) {
            statement.setMaxRows(200);
            try (ResultSet rows = statement.executeQuery(args[3])) {
                ResultSetMetaData meta = rows.getMetaData();
                int columns = meta.getColumnCount();
                StringBuilder header = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    if (column > 1) { header.append(" | "); }
                    header.append(meta.getColumnLabel(column));
                }
                System.out.println(header);
                System.out.println("-".repeat(Math.min(header.length(), 160)));
                while (rows.next()) {
                    StringBuilder line = new StringBuilder();
                    for (int column = 1; column <= columns; column++) {
                        if (column > 1) { line.append(" | "); }
                        Object value = rows.getObject(column);
                        String text = value == null ? "" : value.toString();
                        if (text.length() > 60) { text = text.substring(0, 60) + "…"; }
                        line.append(text);
                    }
                    System.out.println(line);
                }
            }
        }
    }
}
'@
[System.IO.File]::WriteAllText($probe, $source, (New-Object System.Text.UTF8Encoding($false)))

try {
    & java "-Dfile.encoding=UTF-8" -cp $driver.FullName $probe $url $user $password $trimmedStatement
    if ($LASTEXITCODE -ne 0) { exit 1 }
}
finally {
    Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
}
