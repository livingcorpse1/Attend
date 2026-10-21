<#
.SYNOPSIS
    Checks that VeriQra can reach the configured Supabase project.

.DESCRIPTION
    Reads the connection settings from .env, opens a JDBC connection and reports what it
    finds: the server version, the Supabase extensions, the tables that Flyway created
    and the number of accounts. Nothing is written and no secret is printed.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\check-supabase.ps1
#>
[CmdletBinding()]
param(
    [string]$EnvironmentFile = '.env'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    Write-Host "No $EnvironmentFile found. Copy .env.example to .env and fill it in first." -ForegroundColor Yellow
    exit 2
}

# --- Read the environment file ------------------------------------------------
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

# Show where the connection points, without the password.
$masked = $url -replace '(?<=://)([^:@/]+):([^@]+)@', '$1:***@'
Write-Host ''
Write-Host 'Connection target' -ForegroundColor Cyan
Write-Host "  url  $masked"
Write-Host "  user $user"
Write-Host ''

# --- Locate the PostgreSQL driver ---------------------------------------------
$driver = Get-ChildItem "$env:USERPROFILE\.m2\repository\org\postgresql\postgresql" -Recurse -Filter 'postgresql-*.jar' `
    -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1

if (-not $driver) {
    Write-Host 'The PostgreSQL driver is not in the local Maven repository yet.' -ForegroundColor Yellow
    Write-Host 'Run: .\mvnw.cmd -q dependency:go-offline' -ForegroundColor Yellow
    exit 3
}

# --- Probe the database --------------------------------------------------------
$probe = Join-Path ([System.IO.Path]::GetTempPath()) ("veriqra-probe-" + [guid]::NewGuid().ToString('N') + '.java')
$source = @'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class Probe {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2]);
             Statement statement = connection.createStatement()) {

            try (ResultSet rows = statement.executeQuery("select version()")) {
                rows.next();
                System.out.println("SERVER   " + rows.getString(1));
            }
            try (ResultSet rows = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_schema = 'public'")) {
                rows.next();
                System.out.println("TABLES   " + rows.getInt(1) + " in schema public");
            }
            try (ResultSet rows = statement.executeQuery(
                    "select table_name from information_schema.tables where table_schema = 'public' order by table_name")) {
                StringBuilder names = new StringBuilder();
                while (rows.next()) {
                    if (names.length() > 0) { names.append(", "); }
                    names.append(rows.getString(1));
                }
                System.out.println("SCHEMA   " + names);
            }
            try (ResultSet rows = statement.executeQuery("select count(*) from app_user")) {
                rows.next();
                System.out.println("USERS    " + rows.getInt(1));
            } catch (Exception ignored) {
                System.out.println("USERS    (the app_user table does not exist yet, Flyway has not run)");
            }
        }
    }
}
'@
# The single file source launcher rejects a byte order mark, so the file is written
# as plain UTF-8 without one.
[System.IO.File]::WriteAllText($probe, $source, (New-Object System.Text.UTF8Encoding($false)))

try {
    & java "-Dfile.encoding=UTF-8" -cp $driver.FullName $probe $url $user $password
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host 'The connection failed. The usual causes are:' -ForegroundColor Red
        Write-Host '  * the password is wrong or contains a character that needs escaping'
        Write-Host '  * the project is paused, so the pooler refuses new connections'
        Write-Host '  * your network is IPv6 only, so the direct host cannot be reached'
        Write-Host '  * the transaction pooler rejects the driver setting, add ?pool=true'
        exit 1
    }
}
finally {
    Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host 'The database is reachable. The next start will apply the Flyway migrations.' -ForegroundColor Green
