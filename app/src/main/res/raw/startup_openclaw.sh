#!/bin/bash
# ==========================================
# Openclaw Termux Deployment Script v2.0
# ==========================================
#
# Usage: curl -sL https://s.zhihai.me/openclaw > openclaw-install.sh && bash openclaw-install.sh [options]
#
# Options:
#   --help, -h       Show help information
#   --verbose, -v    Enable verbose output (shows command execution details)
#   --dry-run, -d    Dry run mode (simulate execution without making changes)
#   --uninstall, -u  Uninstall Openclaw and clean up configurations
#   --update, -U     Force update Openclaw to latest version without prompting
#
# Examples:
#   curl -sL https://s.zhihai.me/openclaw > openclaw-install.sh && bash openclaw-install.sh
#   curl -sL https://s.zhihai.me/openclaw > openclaw-install.sh && bash openclaw-install.sh --verbose
#   curl -sL https://s.zhihai.me/openclaw > openclaw-install.sh && bash openclaw-install.sh --dry-run
#   curl -sL https://s.zhihai.me/openclaw > openclaw-install.sh && bash openclaw-install.sh --uninstall
#   curl -sL https://s.zhihai.me/openclaw > openclaw-install.sh && bash openclaw-install.sh --update
#
# Note: For direct local execution, use: bash install-openclaw-termux.sh [options]
#
# ==========================================

set -e
set -o pipefail

# Parse command line options
VERBOSE=0
DRY_RUN=0
UNINSTALL=0
FORCE_UPDATE=0
while [[ $# -gt 0 ]]; do
    case $1 in
        --verbose|-v)
            VERBOSE=1
            shift
            ;;
        --dry-run|-d)
            DRY_RUN=1
            shift
            ;;
        --uninstall|-u)
            UNINSTALL=1
            shift
            ;;
        --update|-U)
            FORCE_UPDATE=1
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  --verbose, -v    Enable verbose output"
            echo "  --dry-run, -d    Dry run mode (do not execute commands)"
            echo "  --uninstall, -u  Uninstall Openclaw and related config"
            echo "  --update, -U     Force update to latest version"
            echo "  --help, -h       Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage"
            exit 1
            ;;
    esac
done

trap 'echo -e "${RED}Error: script execution failed. Check logs above.${NC}"; exit 1' ERR

# ==========================================
# Openclaw Termux Deployment Script v2.0
# ==========================================

# Function definitions
check_deps() {
    # Check and install basic dependencies
    log "Checking base environment"
    echo -e "${YELLOW}[1/6] Checking base runtime environment...${NC}"

    # Check if pkg update is needed (run at most once per day)
    UPDATE_FLAG="$HOME/.pkg_last_update"
    if [ ! -f "$UPDATE_FLAG" ] || [ $(($(date +%s) - $(stat -c %Y "$UPDATE_FLAG" 2>/dev/null || echo 0))) -gt 86400 ]; then
        log "Running pkg update"
        echo -e "${YELLOW}Updating package lists...${NC}"
        run_cmd pkg update -y
        if [ $? -ne 0 ]; then
            log "pkg update failed"
            echo -e "${RED}Error: pkg update failed${NC}"
            exit 1
        fi
        run_cmd touch "$UPDATE_FLAG"
        log "pkg update completed"
    else
        log "Skipping pkg update (already up to date)"
        echo -e "${GREEN}Package list is already up to date${NC}"
    fi

    NODE_VERSION=$(node --version 2>/dev/null | sed 's/v//' | cut -d. -f1)
    if [ -z "$NODE_VERSION" ] || [ "$NODE_VERSION" -lt 22 ]; then
        log "Node.js version check failed: $NODE_VERSION, trying auto-install"
        echo -e "${YELLOW}Node.js is missing or below v22. Trying auto-install...${NC}"

        if command -v pkg >/dev/null 2>&1; then
            run_cmd pkg update -y
            run_cmd pkg install nodejs -y
        elif command -v apk >/dev/null 2>&1; then
            run_cmd apk update
            run_cmd apk add nodejs npm
        else
            log "No pkg/apk found to auto-install nodejs"
            echo -e "${RED}Error: cannot auto-install Node.js (pkg/apk not found)${NC}"
            exit 1
        fi

        hash -r
        NODE_VERSION=$(node --version 2>/dev/null | sed 's/v//' | cut -d. -f1)
        if [ -z "$NODE_VERSION" ] || [ "$NODE_VERSION" -lt 22 ]; then
            log "Node.js still invalid after auto-install: $NODE_VERSION"
            echo -e "${RED}Error: Node.js version must be >= 22. Current: $(node --version 2>/dev/null || echo 'unknown')${NC}"
            exit 1
        fi
    fi
    log "Node.js version check passed: $(node --version 2>/dev/null || echo 'unknown')"
    echo -e "${BLUE}Node.js version: $(node -v 2>/dev/null || echo 'not installed')${NC}"
    echo -e "${BLUE}NPM version: $(npm -v 2>/dev/null || echo 'not installed')${NC}"

    DEPS=("nodejs" "git" "openssh" "tmux" "termux-api" "termux-tools" "cmake" "python" "golang" "which")
    MISSING_DEPS=()

    for dep in "${DEPS[@]}"; do
        cmd=$dep
        if [ "$dep" = "nodejs" ]; then cmd="node"; fi
        if ! command -v $cmd &> /dev/null; then
            MISSING_DEPS+=($dep)
        fi
    done

    touch "$BASHRC" 2>/dev/null

    log "Configuring NPM registry"
    npm config set registry https://registry.npmmirror.com
    if [ $? -ne 0 ]; then
        log "Failed to configure NPM registry"
        echo -e "${RED}Error: failed to configure NPM registry${NC}"
        exit 1
    fi

    if [ ${#MISSING_DEPS[@]} -ne 0 ]; then
        log "Missing dependencies: ${MISSING_DEPS[*]}"
        echo -e "${YELLOW}Missing dependencies: ${MISSING_DEPS[*]}${NC}"
        run_cmd pkg upgrade -y
        if [ $? -ne 0 ]; then
            log "pkg upgrade failed"
            echo -e "${RED}Error: pkg upgrade failed${NC}"
            exit 1
        fi
        run_cmd pkg install ${MISSING_DEPS[*]} -y
        if [ $? -ne 0 ]; then
            log "Dependency installation failed"
            echo -e "${RED}Error: dependency installation failed${NC}"
            exit 1
        fi
        log "Dependencies installed successfully"
    else
        log "All dependencies are already installed"
        echo -e "${GREEN}Base environment is ready${NC}"
    fi
}

configure_npm() {
    # Configure NPM environment and install Openclaw
    log "Configuring NPM environment"
    echo -e "\n${YELLOW}[2/6] Configuring Openclaw...${NC}"

    # Configure global NPM environment
    mkdir -p "$NPM_GLOBAL"
    npm config set prefix "$NPM_GLOBAL"
    if [ $? -ne 0 ]; then
        log "Failed to set NPM prefix"
        echo -e "${RED}Error: failed to set NPM prefix${NC}"
        exit 1
    fi
    grep -qxF "export PATH=$NPM_BIN:$PATH" "$BASHRC" || echo "export PATH=$NPM_BIN:$PATH" >> "$BASHRC"
    export PATH="$NPM_BIN:$PATH"

    # Create required directories for Termux compatibility
    log "Creating required Termux directories"
    mkdir -p "$LOG_DIR" "$HOME/tmp"
    if [ $? -ne 0 ]; then
        log "Failed to create required directories"
        echo -e "${RED}Error: failed to create required directories${NC}"
        exit 1
    fi

    # Check/install/update Openclaw
    INSTALLED_VERSION=""
    LATEST_VERSION=""
    NEED_UPDATE=0

    log "Checking Openclaw installation status"
    if [ -f "$NPM_BIN/openclaw" ]; then
        log "Openclaw already installed; checking version"
        echo -e "${BLUE}Checking Openclaw version...${NC}"
        INSTALLED_VERSION=$(npm list -g openclaw --depth=0 2>/dev/null | grep -oE 'openclaw@[0-9]+\.[0-9]+\.[0-9]+' | cut -d@ -f2)
        if [ -z "$INSTALLED_VERSION" ]; then
            log "Failed to parse installed version; trying fallback"
            INSTALLED_VERSION=$(npm view openclaw version 2>/dev/null || echo "unknown")
        fi
        echo -e "${BLUE}Current version: $INSTALLED_VERSION${NC}"

        # Fetch latest version
        log "Fetching latest Openclaw version"
        echo -e "${BLUE}Fetching latest version from npm...${NC}"
        LATEST_VERSION=$(npm view openclaw version 2>/dev/null || echo "")

        if [ -z "$LATEST_VERSION" ]; then
            log "Failed to fetch latest Openclaw version"
            echo -e "${YELLOW}Warning: unable to fetch latest version info; keeping current version${NC}"
        else
            echo -e "${BLUE}Latest version: $LATEST_VERSION${NC}"

            # Simple version compare
            if [ "$INSTALLED_VERSION" != "$LATEST_VERSION" ]; then
                log "New version available: $LATEST_VERSION (current: $INSTALLED_VERSION)"
                echo -e "${YELLOW}Update available: $LATEST_VERSION (current: $INSTALLED_VERSION)${NC}"

                if [ $FORCE_UPDATE -eq 1 ]; then
                    log "Force update enabled; updating Openclaw"
                    echo -e "${YELLOW}Updating Openclaw...${NC}"
                    run_cmd env NODE_LLAMA_CPP_SKIP_DOWNLOAD=true npm i -g openclaw
                    if [ $? -ne 0 ]; then
                        log "Openclaw update failed"
                        echo -e "${RED}Error: Openclaw update failed${NC}"
                        exit 1
                    fi
                    log "Openclaw update completed"
                    echo -e "${GREEN}Openclaw updated to $LATEST_VERSION${NC}"
                else
                    read -p "Update to latest version? (y/n) [default: y]: " UPDATE_CHOICE
                    UPDATE_CHOICE=${UPDATE_CHOICE:-y}

                    if [ "$UPDATE_CHOICE" = "y" ] || [ "$UPDATE_CHOICE" = "Y" ]; then
                        log "Updating Openclaw"
                        echo -e "${YELLOW}Updating Openclaw...${NC}"
                        run_cmd env NODE_LLAMA_CPP_SKIP_DOWNLOAD=true npm i -g openclaw
                        if [ $? -ne 0 ]; then
                            log "Openclaw update failed"
                            echo -e "${RED}Error: Openclaw update failed${NC}"
                            exit 1
                        fi
                        log "Openclaw update completed"
                        echo -e "${GREEN}Openclaw updated to $LATEST_VERSION${NC}"
                    else
                        log "User skipped update"
                        echo -e "${YELLOW}Skipping update; keeping current version${NC}"
                    fi
                fi
            else
                log "Openclaw is already up to date"
                echo -e "${GREEN}Openclaw is up to date: $INSTALLED_VERSION${NC}"
            fi
        fi
    else
        log "Installing Openclaw"
        echo -e "${YELLOW}Installing Openclaw...${NC}"
        # Install Openclaw (silent mode)
        # Skip node-llama-cpp download/build in Termux environment
        run_cmd env NODE_LLAMA_CPP_SKIP_DOWNLOAD=true npm i -g openclaw
        if [ $? -ne 0 ]; then
            log "Openclaw installation failed"
            echo -e "${RED}Error: Openclaw installation failed${NC}"
            exit 1
        fi
        log "Openclaw installation completed"
        INSTALLED_VERSION=$(npm list -g openclaw --depth=0 2>/dev/null | grep -oE 'openclaw@[0-9]+\.[0-9]+\.[0-9]+' | cut -d@ -f2)
        if [ -z "$INSTALLED_VERSION" ]; then
            INSTALLED_VERSION=$(npm view openclaw version 2>/dev/null || echo "unknown")
        fi
        echo -e "${GREEN}Openclaw installed (version: $INSTALLED_VERSION)${NC}"
    fi

    BASE_DIR="$NPM_GLOBAL/lib/node_modules/openclaw"
}

apply_patches() {
    # Apply Android compatibility patches
    log "Applying compatibility patches"
    echo -e "${YELLOW}[3/6] Applying Android compatibility patches...${NC}"

    # Fix hardcoded /tmp/openclaw paths
    log "Searching and fixing hardcoded /tmp/openclaw paths"
    
    # Search all files under openclaw/dist that contain /tmp/openclaw
    cd "$BASE_DIR"
    FILES_WITH_TMP=$(grep -rl "/tmp/openclaw" dist/ 2>/dev/null || true)
    
    if [ -n "$FILES_WITH_TMP" ]; then
        log "Found files that need patching"
        for file in $FILES_WITH_TMP; do
            log "Patching file: $file"
            node -e "const fs = require('fs'); const file = '$BASE_DIR/$file'; let c = fs.readFileSync(file, 'utf8'); c = c.replace(/\/tmp\/openclaw/g, process.env.HOME + '/openclaw-logs'); fs.writeFileSync(file, c);"
        done
        log "Finished patching path references"
    else
        log "No files need patching"
    fi
    
    # Verify patch result
    REMAINING=$(grep -r "/tmp/openclaw" dist/ 2>/dev/null || true)
    if [ -n "$REMAINING" ]; then
        log "Patch verification failed: /tmp/openclaw still exists"
        echo -e "${RED}Warning: some files still contain /tmp/openclaw${NC}"
        echo -e "${YELLOW}Affected files:${NC}"
        echo "$REMAINING"
    else
        log "Patch verification succeeded"
        echo -e "${GREEN}Replaced /tmp/openclaw with $HOME/openclaw-logs${NC}"
    fi

    # Patch clipboard implementation
    CLIP_FILE="$BASE_DIR/node_modules/@mariozechner/clipboard/index.js"
    if [ -f "$CLIP_FILE" ]; then
        log "Applying clipboard compatibility patch"
        node -e "const fs = require('fs'); const file = '$CLIP_FILE'; const mock = 'module.exports = { availableFormats:()=>[], getText:()=>\"\", setText:()=>false, hasText:()=>false, getImageBinary:()=>null, getImageBase64:()=>null, setImageBinary:()=>false, setImageBase64:()=>false, hasImage:()=>false, getHtml:()=>\"\", setHtml:()=>false, hasHtml:()=>false, getRtf:()=>\"\", setRtf:()=>false, hasRtf:()=>false, clear:()=>{}, watch:()=>({stop:()=>{}}), callThreadsafeFunction:()=>{} };'; fs.writeFileSync(file, mock);"
        if [ $? -ne 0 ]; then
            log "Clipboard patch apply failed"
            echo -e "${RED}Error: failed to apply clipboard patch${NC}"
            exit 1
        fi
        # Verify patch result
        if ! grep -q "availableFormats" "$CLIP_FILE"; then
            log "Clipboard patch verification failed"
            echo -e "${RED}Error: clipboard patch verification failed${NC}"
            exit 1
        fi
        log "Clipboard patch applied successfully"
    fi
}

install_wechat_plugin() {
    # Install OpenClaw WeChat UI channel plugin
    log "Installing OpenClaw WeChat UI plugin"
    echo -e "${YELLOW}[4/7] Installing WeChat plugin...${NC}"

    PLUGIN_NAME="openclaw-wechatui-channel"
    PLUGIN_TAR="$HOME/openclaw-wechatui-channel.tar"
    PLUGIN_EXTRACT_DIR="$HOME/openclaw-wechatui-channel"

    if [ ! -f "$PLUGIN_TAR" ]; then
        log "Plugin tar not found: $PLUGIN_TAR"
        echo -e "${RED}Error: plugin tar not found at $PLUGIN_TAR${NC}"
        exit 1
    fi

    # Extract bundled plugin package under ~
    run_cmd rm -rf "$PLUGIN_EXTRACT_DIR"
    run_cmd tar -xf "$PLUGIN_TAR" -C "$HOME"
    if [ $? -ne 0 ]; then
        log "Failed to extract plugin tar: $PLUGIN_TAR"
        echo -e "${RED}Error: failed to extract $PLUGIN_TAR${NC}"
        exit 1
    fi

    if [ ! -d "$PLUGIN_EXTRACT_DIR" ]; then
        # Fallback in case tar has a different top-level folder.
        ROOT_DIR=$(tar -tf "$PLUGIN_TAR" 2>/dev/null | head -n1 | cut -d/ -f1)
        if [ -n "$ROOT_DIR" ] && [ -d "$HOME/$ROOT_DIR" ]; then
            PLUGIN_EXTRACT_DIR="$HOME/$ROOT_DIR"
        else
            log "Extracted plugin directory not found"
            echo -e "${RED}Error: extracted plugin directory not found${NC}"
            exit 1
        fi
    fi

    # Resolve runtime commands.
    # Some environments may not expose npm_execpath and OpenClaw may fallback to /bin/npm.
    NODE_CMD=$(command -v node 2>/dev/null || true)
    NPM_CMD=$(command -v npm 2>/dev/null || true)
    if [ -z "$NPM_CMD" ] && [ -x "$PREFIX/bin/npm" ]; then
        NPM_CMD="$PREFIX/bin/npm"
    fi
    OPENCLAW_CMD=$(command -v openclaw 2>/dev/null || true)
    if [ -z "$NODE_CMD" ] || [ -z "$NPM_CMD" ] || [ -z "$OPENCLAW_CMD" ]; then
        log "Missing runtime command(s): node=$NODE_CMD npm=$NPM_CMD openclaw=$OPENCLAW_CMD"
        echo -e "${RED}Error: missing node/npm/openclaw command before plugin install${NC}"
        exit 1
    fi

    # Install plugin dependencies (excluding dev dependencies).
    run_cmd sh -c "cd \"$PLUGIN_EXTRACT_DIR\" && PATH=\"$NPM_BIN:\$PATH\" npm install --omit=dev --no-audit --no-fund"
    if [ $? -ne 0 ]; then
        log "Failed to run npm install in plugin directory: $PLUGIN_EXTRACT_DIR"
        echo -e "${RED}Error: npm install failed for WeChat plugin${NC}"
        exit 1
    fi


    # Link global openclaw package into plugin node_modules to avoid local re-install.
    GLOBAL_NODE_MODULES=$(PATH="$NPM_BIN:$PATH" npm root -g 2>/dev/null | tr -d '\r')
    if [ -z "$GLOBAL_NODE_MODULES" ]; then
        GLOBAL_NODE_MODULES="$NPM_GLOBAL/lib/node_modules"
    fi
    GLOBAL_OPENCLAW_DIR="$GLOBAL_NODE_MODULES/openclaw"
    if [ ! -d "$GLOBAL_OPENCLAW_DIR" ]; then
        log "Global openclaw package not found: $GLOBAL_OPENCLAW_DIR"
        echo -e "${RED}Error: global openclaw package not found for plugin link${NC}"
        exit 1
    fi
    run_cmd mkdir -p "$PLUGIN_EXTRACT_DIR/node_modules"
    run_cmd rm -rf "$PLUGIN_EXTRACT_DIR/node_modules/openclaw"
    run_cmd ln -sfn "$GLOBAL_OPENCLAW_DIR" "$PLUGIN_EXTRACT_DIR/node_modules/openclaw"
    if [ $? -ne 0 ]; then
        log "Failed to link global openclaw into plugin node_modules"
        echo -e "${RED}Error: failed to link global openclaw dependency${NC}"
        exit 1
    fi

    # If plugin is already registered, skip re-running plugin install command.
    if env PATH="$NPM_BIN:$PATH" "$OPENCLAW_CMD" plugins list 2>/dev/null | grep -Eq "(^|[[:space:]])$PLUGIN_NAME([[:space:]]|$)"; then
        log "Plugin already registered; dependencies and symlink refreshed: $PLUGIN_NAME"
        echo -e "${GREEN}Plugin already installed; refreshed dependencies and symlink: $PLUGIN_NAME${NC}"
        return 0
    fi

    # Install plugin via symlink mode.
    PLUGIN_LINK_NAME=$(basename "$PLUGIN_EXTRACT_DIR")
    run_cmd sh -c "cd \"$HOME\" && PATH=\"$NPM_BIN:\$PATH\" npm_execpath=\"$NPM_CMD\" npm_node_execpath=\"$NODE_CMD\" openclaw plugins install -l \"$PLUGIN_LINK_NAME\""
    if [ $? -ne 0 ]; then
        log "Symlink install failed by name, retrying with absolute path"
        run_cmd env PATH="$NPM_BIN:$PATH" npm_execpath="$NPM_CMD" npm_node_execpath="$NODE_CMD" openclaw plugins install -l "$PLUGIN_EXTRACT_DIR"
        if [ $? -ne 0 ]; then
            log "Failed to install plugin in symlink mode: $PLUGIN_EXTRACT_DIR"
            echo -e "${RED}Error: openclaw plugin symlink installation failed${NC}"
            exit 1
        fi
    fi

    log "OpenClaw WeChat UI plugin installed successfully"
    echo -e "${GREEN}WeChat plugin installed (symlink): $PLUGIN_LINK_NAME${NC}"
}

setup_autostart() {
    # Configure autostart and aliases
    if [ "$AUTO_START" == "y" ]; then
        log "Configuring auto-start"
        # Backup ~/.bashrc before edits
        run_cmd cp "$BASHRC" "$BASHRC.backup"
        run_cmd sed -i '/# --- Openclaw Start ---/,/# --- Openclaw End ---/d' "$BASHRC"
        if [ $? -ne 0 ]; then
            log "Failed to modify bashrc"
            echo -e "${RED}Error: failed to modify bashrc${NC}"
            exit 1
        fi
        cat << EOT >> "$BASHRC"
# --- Openclaw Start ---
# WARNING: This section contains your access token - keep ~/.bashrc secure
export TERMUX_VERSION=1
export TMPDIR=\$HOME/tmp
export OPENCLAW_GATEWAY_TOKEN=$TOKEN
export PATH=\$NPM_BIN:\$PATH
sshd 2>/dev/null
termux-wake-lock 2>/dev/null
alias ocr="pkill -9 -f 'openclaw' 2>/dev/null; tmux kill-session -t openclaw 2>/dev/null; sleep 1; tmux new -d -s openclaw; sleep 1; tmux send-keys -t openclaw \"export PATH=$NPM_BIN:\$PATH TMPDIR=\$HOME/tmp; export OPENCLAW_GATEWAY_TOKEN=$TOKEN; openclaw gateway --bind lan --port $PORT --token \\\$OPENCLAW_GATEWAY_TOKEN --allow-unconfigured\" C-m"
alias oclog='tmux attach -t openclaw'
alias ockill='pkill -9 -f "openclaw" 2>/dev/null; tmux kill-session -t openclaw 2>/dev/null'
# --- OpenClaw End ---
EOT

        source "$BASHRC"
        if [ $? -ne 0 ]; then
            log "Warning: failed to source bashrc"
            echo -e "${YELLOW}Warning: failed to source bashrc${NC}"
        fi
        log "Auto-start configuration completed"
    else
        log "Skipping auto-start configuration"
    fi
}

activate_wakelock() {
    # Activate wake lock to prevent sleep
    log "Activating wake lock"
    echo -e "${YELLOW}[4/6] Activating wake lock...${NC}"
    termux-wake-lock 2>/dev/null
    if [ $? -eq 0 ]; then
        log "Wake lock activated"
        echo -e "${GREEN}Wake lock activated${NC}"
    else
        log "Wake lock activation failed"
        echo -e "${YELLOW}Warning: wake lock activation failed, termux-api may be missing${NC}"
    fi
}

start_service() {
    log "Starting service"
    echo -e "${YELLOW}[5/6] Starting service...${NC}"

    # Check if an existing instance is running
    RUNNING_PROCESS=$(pgrep -f "openclaw gateway" 2>/dev/null || true)
    HAS_TMUX_SESSION=$(tmux has-session -t openclaw 2>/dev/null && echo "yes" || echo "no")

    if [ -n "$RUNNING_PROCESS" ] || [ "$HAS_TMUX_SESSION" = "yes" ]; then
        log "Detected existing Openclaw instance"
        echo -e "${YELLOW}Warning: detected a running Openclaw instance${NC}"
        echo -e "${BLUE}Running process IDs: $RUNNING_PROCESS${NC}"
        read -p "Stop old instance and start a new one? (y/n) [default: y]: " RESTART_CHOICE
        RESTART_CHOICE=${RESTART_CHOICE:-y}

        if [ "$RESTART_CHOICE" = "y" ] || [ "$RESTART_CHOICE" = "Y" ]; then
            log "Stopping old instance"
            echo -e "${YELLOW}Stopping old instance...${NC}"
            # Stop only openclaw-related processes, not all node processes
            pkill -9 -f "openclaw" 2>/dev/null || true
            tmux kill-session -t openclaw 2>/dev/null || true
            sleep 1
        else
            log "User chose not to restart"
            echo -e "${GREEN}Skipping start; keep current instance running${NC}"
            return 0
        fi
    fi

    # Ensure temp directory exists
    mkdir -p "$HOME/tmp"
    export TMPDIR="$HOME/tmp"

    # Create tmux session and start service command
    # Start shell first, then run command inside shell for easier debugging
    tmux new -d -s openclaw
    sleep 1
    
    # Redirect output to runtime log for troubleshooting
    tmux send-keys -t openclaw "export PATH=$NPM_BIN:\$PATH TMPDIR=$HOME/tmp; export OPENCLAW_GATEWAY_TOKEN=$TOKEN; openclaw gateway --bind lan --port $PORT --token \\\$OPENCLAW_GATEWAY_TOKEN --allow-unconfigured 2>&1 | tee $LOG_DIR/runtime.log" C-m
    
    log "Service command sent"
    echo -e "${GREEN}[6/6] Deployment command sent${NC}"
    
    # Verify session startup
    sleep 2
    if tmux has-session -t openclaw 2>/dev/null; then
        echo -e "${GREEN}tmux session created successfully${NC}"
        echo -e "Run ${CYAN}oclog${NC} to view logs, then run openclaw onboard for setup"
    else
        echo -e "${RED}Error: tmux session crashed right after startup${NC}"
        echo -e "Check runtime log: ${YELLOW}cat $LOG_DIR/runtime.log${NC}"
    fi
}

uninstall_openclaw() {
    # Uninstall Openclaw and clean up configurations
    log "Starting uninstallation"
    echo -e "${YELLOW}Uninstalling Openclaw...${NC}"

    # Stop service
    echo -e "${YELLOW}Stopping services...${NC}"
    run_cmd pkill -9 node 2>/dev/null || true
    run_cmd tmux kill-session -t openclaw 2>/dev/null || true
    log "Services stopped"

    # Remove aliases and related config
    echo -e "${YELLOW}Removing aliases and config...${NC}"
    run_cmd sed -i '/# --- Openclaw Start ---/,/# --- Openclaw End ---/d' "$BASHRC"
    run_cmd sed -i '/export PATH=.*\.npm-global\/bin/d' "$BASHRC"
    log "Aliases and config removed"

    # Restore bashrc backup
    if [ -f "$BASHRC.backup" ]; then
        echo -e "${YELLOW}Restoring ~/.bashrc...${NC}"
        run_cmd cp "$BASHRC.backup" "$BASHRC"
        run_cmd rm "$BASHRC.backup"
        log "bashrc restored"
    fi

    # Uninstall npm package
    echo -e "${YELLOW}Uninstalling Openclaw npm package...${NC}"
    run_cmd npm uninstall -g openclaw 2>/dev/null || true
    log "Openclaw npm package uninstalled"

    # Remove logs and config directories
    echo -e "${YELLOW}Removing logs and config directories...${NC}"
    run_cmd rm -rf "$LOG_DIR" 2>/dev/null || true
    run_cmd rm -rf "$NPM_GLOBAL" 2>/dev/null || true
    log "Logs and config directories removed"

    # Remove update marker
    run_cmd rm -f "$HOME/.pkg_last_update" 2>/dev/null || true

    echo -e "${GREEN}Uninstall completed${NC}"
    log "Uninstall completed"
}

# Main script

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Check terminal color support
if [ -t 1 ] && [ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]; then
    : # Color supported, keep ANSI colors
else
    GREEN=''
    BLUE=''
    YELLOW=''
    RED=''
    NC=''
fi

# Common path variables
BASHRC="$HOME/.bashrc"
NPM_GLOBAL="$HOME/.npm-global"
NPM_BIN="$NPM_GLOBAL/bin"
LOG_DIR="$HOME/openclaw-logs"
LOG_FILE="$LOG_DIR/install.log"

# Create log directory
mkdir -p "$LOG_DIR" 2>/dev/null || true

# Log function
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG_FILE"
}

# Command runner with dry-run support
run_cmd() {
    if [ $VERBOSE -eq 1 ]; then
        echo "[VERBOSE] Running: $@"
    fi
    log "Running command: $@"
    if [ $DRY_RUN -eq 1 ]; then
        echo "[DRY-RUN] Skipped: $@"
        return 0
    else
        "$@"
    fi
}

clear
if [ $DRY_RUN -eq 1 ]; then
    echo -e "${YELLOW}[DRY-RUN] Commands will not be executed${NC}"
fi
if [ $VERBOSE -eq 1 ]; then
    echo -e "${BLUE}Verbose mode enabled${NC}"
fi
echo -e "${BLUE}=========================================="
echo -e "   Openclaw Termux Deployment Tool"
echo -e "==========================================${NC}"

# --- default config (no interactive input) ---
PORT=18789
RANDOM_PART=$(date +%s | md5sum | cut -c 1-8)
TOKEN="token$RANDOM_PART"
AUTO_START=y
echo -e "${GREEN}Using default port: $PORT${NC}"
echo -e "${GREEN}Using default random token: $TOKEN${NC}"
echo -e "${GREEN}Using default auto-start: $AUTO_START${NC}"

# Execute steps
if [ $UNINSTALL -eq 1 ]; then
    uninstall_openclaw
    exit 0
fi

log "Script started with config: port=$PORT, token=$TOKEN, auto_start=$AUTO_START"
check_deps
configure_npm
apply_patches
install_wechat_plugin
setup_autostart
activate_wakelock
start_service
echo -e "${GREEN}Done. token=$TOKEN. Common commands: oclog (logs), ockill (stop), ocr (restart)${NC}"
log "Script completed"

