@echo off


SET BASE_PATH=C:\Users\pc\jv\sr\main\distributedServices

:: 1. Service Bruit
echo Launching Bruit...
start "SERVICE-BRUIT" cmd /k "mvn -f "%BASE_PATH%\service-bruit\pom.xml" compile exec:java -Dexec.mainClass="com.traffic.bruit.NoiseServer""

:: 2. Service Camera
start "SERVICE-CAMERA" cmd /k "mvn -f "%BASE_PATH%\service-camera\pom.xml" compile exec:java -Dexec.mainClass="com.traffic.camera.CameraServiceMain""

:: 3. Service Flux Vehicules
start "SERVICE-FLUX" cmd /k "mvn -f "%BASE_PATH%\service-flux-vehicules\pom.xml" compile exec:java -Dexec.mainClass="com.traffic.vehicules.TrafficServiceMain""

:: 4. Service Pollution
start "SERVICE-POLLUTION" cmd /k "mvn -f "%BASE_PATH%\service-pollution\pom.xml" compile exec:java -Dexec.mainClass="com.traffic.pollution.PollutionMain""

:: 5. Service Feux
start "SERVICE-FEUX" cmd /k "mvn -f "%BASE_PATH%\service-feux\pom.xml" compile exec:java -Dexec.mainClass="com.traffic.feux.FeuxServer""


pause