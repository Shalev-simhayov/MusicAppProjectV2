# build.ps1 — Compile, package, and deploy Lyrify
# Run this after any code change to produce a working Lyrify.exe in Desktop\Lyrify\
#
# Requires:
#   JDK 21.0.9  at  ~/.jdks/jdk-21.0.9
#   OpenJFX SDK at  ~/.jdks/openjfx-21.0.11_windows-x64_bin-sdk/javafx-sdk-21.0.11

$ErrorActionPreference = "Stop"

# ── Paths ────────────────────────────────────────────────────────────────────
$JDK         = "$env:USERPROFILE\.jdks\jdk-21.0.9"
$JAVAFX_LIB  = "$env:USERPROFILE\.jdks\openjfx-21.0.11_windows-x64_bin-sdk\javafx-sdk-21.0.11\lib"
$JAVAFX_BIN  = "$env:USERPROFILE\.jdks\openjfx-21.0.11_windows-x64_bin-sdk\javafx-sdk-21.0.11\bin"

$PROJECT     = $PSScriptRoot                      # this script lives in the repo root
$SRC         = "$PROJECT\src"
$OUT         = "$PROJECT\out"
$DEPLOY      = "$env:USERPROFILE\Desktop\Lyrify"

$TEMP_INPUT  = "$env:TEMP\lyrify_build_input"
$TEMP_OUTPUT = "$env:TEMP\lyrify_build_output"

# ── Validate tools ────────────────────────────────────────────────────────────
foreach ($tool in @("$JDK\bin\javac.exe", "$JDK\bin\jar.exe", "$JDK\bin\jpackage.exe")) {
    if (-not (Test-Path $tool)) { throw "Missing: $tool" }
}
if (-not (Test-Path $JAVAFX_LIB)) { throw "JavaFX SDK not found at: $JAVAFX_LIB" }

# ── 1. Compile ────────────────────────────────────────────────────────────────
Write-Host "`n[1/4] Compiling Java sources..."
if (-not (Test-Path $OUT)) { New-Item -ItemType Directory $OUT | Out-Null }

$sources = Get-ChildItem -Path $SRC -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
$sourcesFile = "$env:TEMP\lyrify_sources.txt"
[System.IO.File]::WriteAllLines($sourcesFile, $sources)

$ErrorActionPreference = "Continue"
& "$JDK\bin\javac.exe" `
    --module-path $JAVAFX_LIB `
    --add-modules "javafx.controls,javafx.fxml,javafx.graphics,javafx.base" `
    -cp "$PROJECT\jaudiotagger-3.0.1.jar;$PROJECT\json-20240303.jar" `
    -d $OUT `
    "@$sourcesFile"
$compileExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"

if ($compileExit -ne 0) { throw "Compilation failed" }

if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }

# ── 2. Package JAR ────────────────────────────────────────────────────────────
Write-Host "`n[2/4] Creating Lyrify.jar..."

$ErrorActionPreference = "Continue"
$jarCmd = "`"$JDK\bin\jar.exe`" --create --file `"$PROJECT\Lyrify.jar`" -C `"$OUT`" ."
cmd /c $jarCmd
$jarExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"

if ($jarExit -ne 0) { throw "JAR creation failed" }

# ── 3. Run jpackage ───────────────────────────────────────────────────────────
Write-Host "`n[3/4] Running jpackage..."

# Prepare temp input with only the JARs
if (Test-Path $TEMP_INPUT)              { Remove-Item -Recurse -Force $TEMP_INPUT }
if (Test-Path "$TEMP_OUTPUT\Lyrify")   { Remove-Item -Recurse -Force "$TEMP_OUTPUT\Lyrify" }
New-Item -ItemType Directory -Force $TEMP_INPUT  | Out-Null
New-Item -ItemType Directory -Force $TEMP_OUTPUT | Out-Null

Copy-Item "$PROJECT\Lyrify.jar"              $TEMP_INPUT
Copy-Item "$PROJECT\jaudiotagger-3.0.1.jar"  $TEMP_INPUT
Copy-Item "$PROJECT\json-20240303.jar"        $TEMP_INPUT

& "$JDK\bin\jpackage.exe" `
    --type app-image `
    --name Lyrify `
    --app-version 1.0 `
    --input $TEMP_INPUT `
    --main-jar Lyrify.jar `
    --main-class lyrify.App.MainApp `
    --module-path $JAVAFX_LIB `
    --add-modules "javafx.controls,javafx.fxml,javafx.graphics,javafx.base,java.desktop,java.prefs,java.scripting,java.xml,jdk.unsupported" `
    --java-options "--add-opens=javafx.base/com.sun.javafx.reflect=ALL-UNNAMED" `
    --dest $TEMP_OUTPUT

if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

# ── 4. Deploy ─────────────────────────────────────────────────────────────────
Write-Host "`n[4/4] Deploying to $DEPLOY..."

# Update runtime (jpackage creates it without JavaFX native DLLs when using SDK JARs)
if (Test-Path "$DEPLOY\runtime") { Remove-Item -Recurse -Force "$DEPLOY\runtime" }
Copy-Item -Recurse "$TEMP_OUTPUT\Lyrify\runtime" "$DEPLOY\"

# Copy missing JavaFX native DLLs (jpackage omits these without jmods)
$jfxNativeDlls = @(
    "glass.dll",        # windowing system
    "prism_d3d.dll",    # D3D rendering
    "prism_sw.dll",     # software rendering fallback
    "prism_common.dll", # shared prism code
    "javafx_font.dll",  # font rendering
    "javafx_iio.dll",   # image I/O
    "decora_sse.dll",   # CSS effects engine
    "fxplugins.dll"     # rendering plugins
)
foreach ($dll in $jfxNativeDlls) {
    $src = "$JAVAFX_BIN\$dll"
    if (Test-Path $src) {
        Copy-Item $src "$DEPLOY\runtime\bin\" -Force
    }
}

# Update launcher exe
Copy-Item "$TEMP_OUTPUT\Lyrify\Lyrify.exe" "$DEPLOY\" -Force

# Update jpackage-generated files in app dir (preserve source files)
Copy-Item "$TEMP_OUTPUT\Lyrify\app\Lyrify.cfg"      "$DEPLOY\app\" -Force
Copy-Item "$TEMP_OUTPUT\Lyrify\app\.jpackage.xml"   "$DEPLOY\app\" -Force -ErrorAction SilentlyContinue

# Copy fpcalc for AcoustID fingerprinting
Copy-Item "$PROJECT\chromaprint-fpcalc-1.6.0-windows-x86_64\fpcalc.exe" "$DEPLOY\app\fpcalc.exe" -Force

# Cleanup
Remove-Item -Recurse -Force $TEMP_INPUT
Remove-Item -Recurse -Force $TEMP_OUTPUT
Remove-Item $sourcesFile -ErrorAction SilentlyContinue

Write-Host "`nBuild complete! Run: $DEPLOY\Lyrify.exe"
