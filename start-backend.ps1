Set-Location "$PSScriptRoot\backend"
mvn -s maven-settings.xml clean install -DskipTests
Write-Host "Build complete. Start services in separate terminals:"
Write-Host "mvn -s maven-settings.xml -pl user-service spring-boot:run"
Write-Host "mvn -s maven-settings.xml -pl knowledge-service spring-boot:run"
Write-Host "mvn -s maven-settings.xml -pl community-service spring-boot:run"
Write-Host "mvn -s maven-settings.xml -pl message-service spring-boot:run"
Write-Host "mvn -s maven-settings.xml -pl gateway spring-boot:run"
