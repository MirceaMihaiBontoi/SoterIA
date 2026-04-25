# run_test.ps1
# SoterIA Emergency Triage Stress Test Runner

# 1. Clean up stale processes and locks
Stop-Process -Name "java" -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 2. Reset the indexing cache to ensure fresh protocol loading
Remove-Item -Path "target/temp/soteria-index-stable" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path "target/temp" -Force -ErrorAction SilentlyContinue

# 3. Synchronize and Compile
mvn resources:resources compile test-compile

# 4. Run the Stress Test
# Using --% to stop PowerShell from mangling the -D properties
mvn --% exec:java -Dexec.mainClass=com.soteria.infrastructure.intelligence.ClassifierStressTest -Dexec.classpathScope=test -Djava.io.tmpdir=target/temp
