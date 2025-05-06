@echo off
cls

echo =============================================
echo        Building and Running Coco/R Project...
echo =============================================

cd src

REM Step 1: Delete old generated parser files
del /Q Scanner.java Parser.java

REM Step 2: Generate Scanner.java and Parser.java
echo [*] Running Coco/R on GrammarFile.atg...
java -jar ..\lib\Coco.jar ..\GrammarFile.atg || goto error

REM Step 3: Compile all Java files (including generated ones)
echo [*] Compiling Java sources...
javac *.java || goto error

REM Step 4: Run the program
echo [*] Running FrontEndLauncher...
java FrontEndLauncher || goto error

echo =============================================
echo         Build and Run Complete!
echo =============================================
pause
exit

:error
echo =============================================
echo         An error has occurred!
echo =============================================
pause
exit
