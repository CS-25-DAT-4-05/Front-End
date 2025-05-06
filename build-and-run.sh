#!/bin/bash

# Clear the terminal
clear

echo "========================================"
echo "    Building and Running Coco/R Project..."
echo "========================================"

# Step 0: 
# The first time running this .sh file. Run the following commands in terminal in the Front-End Folder:
# 1. chmod +x build-and-run.sh
# 2. ./build-and-run.sh

# Step 1: Clean old generated files
echo "[*] Cleaning old files..."
rm -f src/Scanner.java src/Parser.java

# Step 2: Generate Scanner and Parser using Coco/R
echo "[*] Running Coco/R on GrammarFile.atg..."
java -jar ./lib/Coco.jar -o src GrammarFile.atg || { echo "Error: Coco/R generation failed"; exit 1; }

# Step 3: Compile Java source files
echo "[*] Compiling Java files..."
javac src/FrontEndLauncher.java src/Parser.java src/Scanner.java || { echo "Error: Compilation failed"; exit 1; }

# Step 4: Run the program
echo "[*] Running FrontEndLauncher..."
java -cp src FrontEndLauncher || { echo "Error: Program execution failed"; exit 1; }

echo "========================================"
echo "        Build and Run Complete!"
echo "========================================"
