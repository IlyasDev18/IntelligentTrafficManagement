@echo off
SET BASE_PATH=C:\Users\pc\jv\sr\main\data-pipeline


start "KAFKA-ENGINE" cmd /k "C:\kafka\bin\windows\kafka-server-start.bat C:\kafka\config\server.properties"
 


:: Launch Producer first
echo Launching Producer...
start "KAFKA-PRODUCER" cmd /k "mvn -f "C:\Users\pc\jv\sr\main\data-pipeline\kafka-producer\pom.xml" compile exec:java -Dexec.mainClass="com.traffic.producer.TrafficProducerMain""

:: Wait 3 seconds for the Producer to initialize topics
timeout /t 3

:: Launch Consumer
echo Launching Analysis Consumer...
start "CONSUMER-ANALYSIS" cmd /k "mvn -f "C:\Users\pc\jv\sr\main\data-pipeline\kafka-consumer\pom.xml" compile exec:java -Dexec.mainClass="com.traffic.consumer.TrafficAnalysisConsumerMain""

echo.
echo Systems are running locally (No Docker).
::mvn spring-boot:run