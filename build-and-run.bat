@echo off
cls

echo ==========================================
echo    Building and Running Coco/R Project...
echo ==========================================

REM Step 1: Clean old generated parser files
echo [*] Cleaning old files...
del /Q src\Scanner.java src\Parser.java

REM Step 2: Generate Scanner and Parser using Coco/R
echo [*] Running Coco/R on GrammarFile.atg...
java -jar .\lib\Coco.jar -o src GrammarFile.atg || goto error

REM Step 3: Compile Java source files
echo [*] Compiling Java files...
javac src\FrontEndLauncher.java src\Parser.java src\Scanner.java || goto error

REM Step 4: Run the program
echo [*] Running FrontEndLauncher...
java -cp src FrontEndLauncher || goto error

echo ==========================================
echo         Build and Run Complete!
echo ==========================================
pause
exit

:error
echo ==========================================
echo         An error has occurred!
echo ==========================================
pause
exit
