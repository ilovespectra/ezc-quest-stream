#!/bin/bash

# EZC Quest Stream setup for macOS
# Installs only the tools needed for build/install and playback.

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}======================================${NC}"
echo -e "${BLUE}EZC Quest Stream Setup${NC}"
echo -e "${BLUE}======================================${NC}"
echo ""

if ! command -v brew >/dev/null 2>&1; then
    echo -e "${RED}Homebrew not found. Install it first:${NC}"
    echo "https://brew.sh"
    exit 1
fi

echo -e "${YELLOW}Installing dependencies...${NC}"
echo ""

if ! command -v adb >/dev/null 2>&1; then
    echo -e "${BLUE}Installing android-platform-tools...${NC}"
    brew install android-platform-tools
else
    echo -e "${GREEN}✓ adb already installed${NC}"
fi

if ! command -v gradle >/dev/null 2>&1; then
    echo -e "${BLUE}Installing gradle...${NC}"
    brew install gradle
else
    echo -e "${GREEN}✓ gradle already installed${NC}"
fi

if ! command -v open >/dev/null 2>&1; then
    echo -e "${YELLOW}The 'open' command was not found (unexpected on macOS).${NC}"
fi

echo ""
echo -e "${GREEN}✓ Setup complete${NC}"
echo ""
echo "Next steps:"
echo "1. Connect Quest with USB and approve USB debugging"
echo "2. Run: bash install.sh"
echo "3. Start stream in headset and open URL on Mac"
