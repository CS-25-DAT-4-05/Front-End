#!/bin/bash

# Run this file from the Front-End folder (Run first time):
# 1. chmod +x build-and-run.sh
# 2. ./build-and-run.sh

clear

echo "=============================================="
echo "       Building and Running Coco/R Project... "
echo "=============================================="

# Move into src folder
cd src || exit

# Step 1: Delete previously generated parser files
echo "[*] Cleaning up old generated files..."
rm -f Scanner.java Parser.java

# Step 2: Generate Scanner.java and Parser.java
echo "[*] Running Coco/R on GrammarFile.atg..."
java -jar ../lib/Coco.jar ../GrammarFile.atg || { echo "Error: Coco/R generation failed"; exit 1; }

# Step 3: Compile all Java source files
echo "[*] Compiling Java files..."
javac *.java || { echo "Error: Compilation failed"; exit 1; }

# Step 4: Run the launcher
echo "[*] Running FrontEndLauncher..."
java FrontEndLauncher || { echo "Error: Program execution failed"; exit 1; }

echo "=============================================="
echo "        Build and Run Complete!               "
echo "=============================================="
