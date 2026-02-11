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

# 解析命令行选项
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
            echo "用法: $0 [选项]"
            echo "选项:"
            echo "  --verbose, -v    启用详细输出"
            echo "  --dry-run, -d    模拟运行，不执行实际命令"
            echo "  --uninstall, -u  卸载 Openclaw 和相关配�?"
            echo "  --update, -U     强制更新到最新版�?"
            echo "  --help, -h       显示此帮助信�?"
            exit 0
            ;;
        *)
            echo "未知选项: $1"
            echo "使用 --help 查看帮助"
            exit 1
            ;;
    esac
done

trap 'echo -e "${RED}错误：脚本执行失败，请检查上述输�?{NC}"; exit 1' ERR

# ==========================================
# Openclaw Termux Deployment Script v2.0
# ==========================================

# Function definitions
check_deps() {
    # Check and install basic dependencies
    log "开始检查基础环境"
    echo -e "${YELLOW}[1/6] 正在检查基础运行环境...${NC}"

    # 检查是否需要更�?pkg（每天只执行一次）
    UPDATE_FLAG="$HOME/.pkg_last_update"
    if [ ! -f "$UPDATE_FLAG" ] || [ $(($(date +%s) - $(stat -c %Y "$UPDATE_FLAG" 2>/dev/null || echo 0))) -gt 86400 ]; then
        log "执行 pkg update"
        echo -e "${YELLOW}更新包列�?..${NC}"
        run_cmd pkg update -y
        if [ $? -ne 0 ]; then
            log "pkg update 失败"
            echo -e "${RED}错误：pkg 更新失败${NC}"
            exit 1
        fi
        run_cmd touch "$UPDATE_FLAG"
        log "pkg update 完成"
    else
        log "跳过 pkg update（已更新�?"
        echo -e "${GREEN}包列表已是最�?{NC}"
    fi

    NODE_VERSION=$(node --version 2>/dev/null | sed 's/v//' | cut -d. -f1)
    if [ -z "$NODE_VERSION" ] || [ "$NODE_VERSION" -lt 22 ]; then
        log "Node.js version check failed: $NODE_VERSION, trying auto-install"
        echo -e "${YELLOW}Node.js 缺失或版本低�?22，尝试自动安�?..${NC}"

        if command -v pkg >/dev/null 2>&1; then
            run_cmd pkg update -y
            run_cmd pkg install nodejs -y
        elif command -v apk >/dev/null 2>&1; then
            run_cmd apk update
            run_cmd apk add nodejs npm
        else
            log "No pkg/apk found to auto-install nodejs"
            echo -e "${RED}错误：无法自动安�?Node.js（未找到 pkg �?apk�?{NC}"
            exit 1
        fi

        hash -r
        NODE_VERSION=$(node --version 2>/dev/null | sed 's/v//' | cut -d. -f1)
        if [ -z "$NODE_VERSION" ] || [ "$NODE_VERSION" -lt 22 ]; then
            log "Node.js still invalid after auto-install: $NODE_VERSION"
            echo -e "${RED}错误：Node.js 版本必须 >= 22，当�? $(node --version 2>/dev/null || echo 'unknown')${NC}"
            exit 1
        fi
    fi
    log "Node.js version check passed: $(node --version 2>/dev/null || echo 'unknown')"
    echo -e "${BLUE}Node.js 版本: $(node -v 2>/dev/null || echo 'not installed')${NC}"
    echo -e "${BLUE}NPM 版本: $(npm -v 2>/dev/null || echo 'not installed')${NC}"

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

    log "设置 NPM 镜像"
    npm config set registry https://registry.npmmirror.com
    if [ $? -ne 0 ]; then
        log "NPM 镜像设置失败"
        echo -e "${RED}错误：NPM 镜像设置失败${NC}"
        exit 1
    fi

    if [ ${#MISSING_DEPS[@]} -ne 0 ]; then
        log "缺失依赖: ${MISSING_DEPS[*]}"
        echo -e "${YELLOW}检查可能的组件缺失: ${MISSING_DEPS[*]}${NC}"
        run_cmd pkg upgrade -y
        if [ $? -ne 0 ]; then
            log "pkg upgrade 失败"
            echo -e "${RED}错误：pkg 升级失败${NC}"
            exit 1
        fi
        run_cmd pkg install ${MISSING_DEPS[*]} -y
        if [ $? -ne 0 ]; then
            log "依赖安装失败"
            echo -e "${RED}错误：依赖安装失�?{NC}"
            exit 1
        fi
        log "依赖安装完成"
    else
        log "所有依赖已安装"
        echo -e "${GREEN}�?基础环境已就�?{NC}"
    fi
}

configure_npm() {
    # Configure NPM environment and install Openclaw
    log "开始配�?NPM"
    echo -e "\n${YELLOW}[2/6] 正在配置 Openclaw...${NC}"

    # 配置 NPM 全局环境
    mkdir -p "$NPM_GLOBAL"
    npm config set prefix "$NPM_GLOBAL"
    if [ $? -ne 0 ]; then
        log "NPM 前缀设置失败"
        echo -e "${RED}错误：NPM 前缀设置失败${NC}"
        exit 1
    fi
    grep -qxF "export PATH=$NPM_BIN:$PATH" "$BASHRC" || echo "export PATH=$NPM_BIN:$PATH" >> "$BASHRC"
    export PATH="$NPM_BIN:$PATH"

    # 在安装前创建必要的目录（Termux 兼容性处理）
    log "创建 Termux 兼容性目�?"
    mkdir -p "$LOG_DIR" "$HOME/tmp"
    if [ $? -ne 0 ]; then
        log "目录创建失败"
        echo -e "${RED}错误：目录创建失�?{NC}"
        exit 1
    fi

    # 检查并安装/更新 Openclaw
    INSTALLED_VERSION=""
    LATEST_VERSION=""
    NEED_UPDATE=0

    log "检�?Openclaw 安装状�?"
    if [ -f "$NPM_BIN/openclaw" ]; then
        log "Openclaw 已安装，检查版�?"
        echo -e "${BLUE}检�?Openclaw 版本...${NC}"
        INSTALLED_VERSION=$(npm list -g openclaw --depth=0 2>/dev/null | grep -oE 'openclaw@[0-9]+\.[0-9]+\.[0-9]+' | cut -d@ -f2)
        if [ -z "$INSTALLED_VERSION" ]; then
            log "版本提取失败，尝试备用方�?"
            INSTALLED_VERSION=$(npm view openclaw version 2>/dev/null || echo "unknown")
        fi
        echo -e "${BLUE}当前版本: $INSTALLED_VERSION${NC}"

        # 获取最新版�?
        log "获取最新版本信�?"
        echo -e "${BLUE}正在�?npm 获取最新版本信�?..${NC}"
        LATEST_VERSION=$(npm view openclaw version 2>/dev/null || echo "")

        if [ -z "$LATEST_VERSION" ]; then
            log "无法获取最新版本信�?"
            echo -e "${YELLOW}⚠️  无法获取最新版本信息（可能是网络问题），保持当前版�?{NC}"
        else
            echo -e "${BLUE}最新版�? $LATEST_VERSION${NC}"

            # 简单版本比�?
            if [ "$INSTALLED_VERSION" != "$LATEST_VERSION" ]; then
                log "发现新版�? $LATEST_VERSION (当前: $INSTALLED_VERSION)"
                echo -e "${YELLOW}🔔 发现新版�? $LATEST_VERSION (当前: $INSTALLED_VERSION)${NC}"

                if [ $FORCE_UPDATE -eq 1 ]; then
                    log "强制更新模式，直接更�?"
                    echo -e "${YELLOW}正在更新 Openclaw...${NC}"
                    run_cmd env NODE_LLAMA_CPP_SKIP_DOWNLOAD=true npm i -g openclaw
                    if [ $? -ne 0 ]; then
                        log "Openclaw 更新失败"
                        echo -e "${RED}错误：Openclaw 更新失败${NC}"
                        exit 1
                    fi
                    log "Openclaw 更新完成"
                    echo -e "${GREEN}�?Openclaw 已更新到 $LATEST_VERSION${NC}"
                else
                    read -p "是否更新到新版本? (y/n) [默认: y]: " UPDATE_CHOICE
                    UPDATE_CHOICE=${UPDATE_CHOICE:-y}

                    if [ "$UPDATE_CHOICE" = "y" ] || [ "$UPDATE_CHOICE" = "Y" ]; then
                        log "开始更�?Openclaw"
                        echo -e "${YELLOW}正在更新 Openclaw...${NC}"
                        run_cmd env NODE_LLAMA_CPP_SKIP_DOWNLOAD=true npm i -g openclaw
                        if [ $? -ne 0 ]; then
                            log "Openclaw 更新失败"
                            echo -e "${RED}错误：Openclaw 更新失败${NC}"
                            exit 1
                        fi
                        log "Openclaw 更新完成"
                        echo -e "${GREEN}�?Openclaw 已更新到 $LATEST_VERSION${NC}"
                    else
                        log "用户选择跳过更新"
                        echo -e "${YELLOW}跳过更新，使用当前版�?{NC}"
                    fi
                fi
            else
                log "版本已是最�?"
                echo -e "${GREEN}�?Openclaw 已是最新版�?$INSTALLED_VERSION${NC}"
            fi
        fi
    else
        log "开始安�?Openclaw"
        echo -e "${YELLOW}正在安装 Openclaw...${NC}"
        # 安装 Openclaw (静默安装)
        # 设置环境变量跳过 node-llama-cpp 下载/编译（Termux 环境不支持）
        run_cmd env NODE_LLAMA_CPP_SKIP_DOWNLOAD=true npm i -g openclaw
        if [ $? -ne 0 ]; then
            log "Openclaw 安装失败"
            echo -e "${RED}错误：Openclaw 安装失败${NC}"
            exit 1
        fi
        log "Openclaw 安装完成"
        INSTALLED_VERSION=$(npm list -g openclaw --depth=0 2>/dev/null | grep -oE 'openclaw@[0-9]+\.[0-9]+\.[0-9]+' | cut -d@ -f2)
        if [ -z "$INSTALLED_VERSION" ]; then
            INSTALLED_VERSION=$(npm view openclaw version 2>/dev/null || echo "unknown")
        fi
        echo -e "${GREEN}�?Openclaw 已安�?(版本: $INSTALLED_VERSION)${NC}"
    fi

    BASE_DIR="$NPM_GLOBAL/lib/node_modules/openclaw"
}

apply_patches() {
    # Apply Android compatibility patches
    log "开始应用补�?"
    echo -e "${YELLOW}[3/6] 正在应用 Android 兼容性补�?..${NC}"

    # 修复所有包�?/tmp/openclaw 路径的文�?
    log "搜索并修复所有硬编码�?/tmp/openclaw 路径"
    
    # �?openclaw 目录中搜索所有包�?/tmp/openclaw 的文�?
    cd "$BASE_DIR"
    FILES_WITH_TMP=$(grep -rl "/tmp/openclaw" dist/ 2>/dev/null || true)
    
    if [ -n "$FILES_WITH_TMP" ]; then
        log "找到需要修复的文件"
        for file in $FILES_WITH_TMP; do
            log "修复文件: $file"
            node -e "const fs = require('fs'); const file = '$BASE_DIR/$file'; let c = fs.readFileSync(file, 'utf8'); c = c.replace(/\/tmp\/openclaw/g, process.env.HOME + '/openclaw-logs'); fs.writeFileSync(file, c);"
        done
        log "所有文件修复完�?"
    else
        log "未找到需要修复的文件"
    fi
    
    # 验证补丁是否生效
    REMAINING=$(grep -r "/tmp/openclaw" dist/ 2>/dev/null || true)
    if [ -n "$REMAINING" ]; then
        log "补丁验证失败，仍有文件包�?/tmp/openclaw"
        echo -e "${RED}警告：部分文件仍包含 /tmp/openclaw 路径${NC}"
        echo -e "${YELLOW}受影响的文件�?{NC}"
        echo "$REMAINING"
    else
        log "补丁验证成功，所有路径已替换"
        echo -e "${GREEN}�?所�?/tmp/openclaw 路径已替换为 $HOME/openclaw-logs${NC}"
    fi

    # 修复剪贴�?
    CLIP_FILE="$BASE_DIR/node_modules/@mariozechner/clipboard/index.js"
    if [ -f "$CLIP_FILE" ]; then
        log "应用剪贴板补�?"
        node -e "const fs = require('fs'); const file = '$CLIP_FILE'; const mock = 'module.exports = { availableFormats:()=>[], getText:()=>\"\", setText:()=>false, hasText:()=>false, getImageBinary:()=>null, getImageBase64:()=>null, setImageBinary:()=>false, setImageBase64:()=>false, hasImage:()=>false, getHtml:()=>\"\", setHtml:()=>false, hasHtml:()=>false, getRtf:()=>\"\", setRtf:()=>false, hasRtf:()=>false, clear:()=>{}, watch:()=>({stop:()=>{}}), callThreadsafeFunction:()=>{} };'; fs.writeFileSync(file, mock);"
        if [ $? -ne 0 ]; then
            log "剪贴板补丁应用失�?"
            echo -e "${RED}错误：剪贴板补丁应用失败${NC}"
            exit 1
        fi
        # 验证补丁是否生效
        if ! grep -q "availableFormats" "$CLIP_FILE"; then
            log "剪贴板补丁验证失�?"
            echo -e "${RED}错误：剪贴板补丁未正确应用，请检查文件内�?{NC}"
            exit 1
        fi
        log "剪贴板补丁应用成�?"
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

    # Skip if plugin already exists.
    if env PATH="$NPM_BIN:$PATH" "$OPENCLAW_CMD" plugins list 2>/dev/null | grep -Eq "(^|[[:space:]])$PLUGIN_NAME([[:space:]]|$)"; then
        log "Plugin already installed, skip install: $PLUGIN_NAME"
        echo -e "${GREEN}Plugin already installed, skip: $PLUGIN_NAME${NC}"
        return 0
    fi

    # Install dependencies first in the extracted plugin directory.
    run_cmd sh -c "cd \"$PLUGIN_EXTRACT_DIR\" && PATH=\"$NPM_BIN:\$PATH\" npm install"
    if [ $? -ne 0 ]; then
        log "Failed to run npm install in plugin directory: $PLUGIN_EXTRACT_DIR"
        echo -e "${RED}Error: npm install failed for WeChat plugin${NC}"
        exit 1
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
    echo -e "${GREEN}�?WeChat plugin installed (symlink): $PLUGIN_LINK_NAME${NC}"
}

setup_autostart() {
    # Configure autostart and aliases
    if [ "$AUTO_START" == "y" ]; then
        log "配置自启�?"
        # 备份�?~/.bashrc 文件
        run_cmd cp "$BASHRC" "$BASHRC.backup"
        run_cmd sed -i '/# --- Openclaw Start ---/,/# --- Openclaw End ---/d' "$BASHRC"
        if [ $? -ne 0 ]; then
            log "bashrc 修改失败"
            echo -e "${RED}错误：bashrc 修改失败${NC}"
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
            log "bashrc 加载警告"
            echo -e "${YELLOW}警告：bashrc 加载失败，可能影响别�?{NC}"
        fi
        log "自启动配置完�?"
    else
        log "跳过自启动配�?"
    fi
}

activate_wakelock() {
    # Activate wake lock to prevent sleep
    log "激活唤醒锁"
    echo -e "${YELLOW}[4/6] 激活唤醒锁...${NC}"
    termux-wake-lock 2>/dev/null
    if [ $? -eq 0 ]; then
        log "唤醒锁激活成�?"
        echo -e "${GREEN}�?Wake-lock 已激�?{NC}"
    else
        log "唤醒锁激活失�?"
        echo -e "${YELLOW}⚠️  Wake-lock 激活失败，可能 termux-api 未正确安�?{NC}"
    fi
}

start_service() {
    log "启动服务"
    echo -e "${YELLOW}[5/6] 启动服务...${NC}"

    # 检查是否有实例在运�?
    RUNNING_PROCESS=$(pgrep -f "openclaw gateway" 2>/dev/null || true)
    HAS_TMUX_SESSION=$(tmux has-session -t openclaw 2>/dev/null && echo "yes" || echo "no")

    if [ -n "$RUNNING_PROCESS" ] || [ "$HAS_TMUX_SESSION" = "yes" ]; then
        log "发现已有 Openclaw 实例在运�?"
        echo -e "${YELLOW}⚠️  检测到 Openclaw 实例已在运行${NC}"
        echo -e "${BLUE}运行中的进程: $RUNNING_PROCESS${NC}"
        read -p "是否停止旧实例并启动新实�? (y/n) [默认: y]: " RESTART_CHOICE
        RESTART_CHOICE=${RESTART_CHOICE:-y}

        if [ "$RESTART_CHOICE" = "y" ] || [ "$RESTART_CHOICE" = "Y" ]; then
            log "停止旧实�?"
            echo -e "${YELLOW}正在停止旧实�?..${NC}"
            # 只停�?openclaw 相关进程，不杀死所�?node 进程
            pkill -9 -f "openclaw" 2>/dev/null || true
            tmux kill-session -t openclaw 2>/dev/null || true
            sleep 1
        else
            log "用户选择不重�?"
            echo -e "${GREEN}跳过启动，保持当前实例运�?{NC}"
            return 0
        fi
    fi

    # 2. 确保目录存在
    mkdir -p "$HOME/tmp"
    export TMPDIR="$HOME/tmp"

    # 3. 创建会话并捕获可能的错误
    # 这里我们先启动一�?shell，再�?shell 里执行命令，方便观察
    tmux new -d -s openclaw
    sleep 1
    
    # 将输出重定向到一个临时文件，如果 tmux 崩了也能看到报错
    tmux send-keys -t openclaw "export PATH=$NPM_BIN:\$PATH TMPDIR=$HOME/tmp; export OPENCLAW_GATEWAY_TOKEN=$TOKEN; openclaw gateway --bind lan --port $PORT --token \\\$OPENCLAW_GATEWAY_TOKEN --allow-unconfigured 2>&1 | tee $LOG_DIR/runtime.log" C-m
    
    log "服务指令已发�?"
    echo -e "${GREEN}[6/6] 部署指令发送完�?{NC}"
    
    # 4. 实时验证
    sleep 2
    if tmux has-session -t openclaw 2>/dev/null; then
        echo -e "${GREEN}�?tmux 会话已建立！${NC}"
        echo -e "请退出终端重新进入后执行: ${CYAN}oclog${NC} 查看日志；执�?openclaw onboard 进行配置"
    else
        echo -e "${RED}�?错误：tmux 会话启动后立即崩溃�?{NC}"
        echo -e "请检查报错日�? ${YELLOW}cat $LOG_DIR/runtime.log${NC}"
    fi
}

uninstall_openclaw() {
    # Uninstall Openclaw and clean up configurations
    log "开始卸�?Openclaw"
    echo -e "${YELLOW}开始卸�?Openclaw...${NC}"

    # 停止服务
    echo -e "${YELLOW}停止服务...${NC}"
    run_cmd pkill -9 node 2>/dev/null || true
    run_cmd tmux kill-session -t openclaw 2>/dev/null || true
    log "服务已停�?"

    # 删除别名和配�?
    echo -e "${YELLOW}删除别名和配�?..${NC}"
    run_cmd sed -i '/# --- Openclaw Start ---/,/# --- Openclaw End ---/d' "$BASHRC"
    run_cmd sed -i '/export PATH=.*\.npm-global\/bin/d' "$BASHRC"
    log "别名和配置已删除"

    # 恢复备份�?bashrc
    if [ -f "$BASHRC.backup" ]; then
        echo -e "${YELLOW}恢复原始 ~/.bashrc...${NC}"
        run_cmd cp "$BASHRC.backup" "$BASHRC"
        run_cmd rm "$BASHRC.backup"
        log "bashrc 已恢�?"
    fi

    # 卸载 npm �?
    echo -e "${YELLOW}卸载 Openclaw �?..${NC}"
    run_cmd npm uninstall -g openclaw 2>/dev/null || true
    log "Openclaw 包已卸载"

    # 删除日志和配置目�?
    echo -e "${YELLOW}删除日志和配置目�?..${NC}"
    run_cmd rm -rf "$LOG_DIR" 2>/dev/null || true
    run_cmd rm -rf "$NPM_GLOBAL" 2>/dev/null || true
    log "日志和配置目录已删除"

    # 删除更新标志
    run_cmd rm -f "$HOME/.pkg_last_update" 2>/dev/null || true

    echo -e "${GREEN}卸载完成�?{NC}"
    log "卸载完成"
}

# 主脚�?

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# 检查终端是否支持颜�?
if [ -t 1 ] && [ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]; then
    : # 支持，保持颜�?
else
    GREEN=''
    BLUE=''
    YELLOW=''
    RED=''
    NC=''
fi

# 定义常用路径变量
BASHRC="$HOME/.bashrc"
NPM_GLOBAL="$HOME/.npm-global"
NPM_BIN="$NPM_GLOBAL/bin"
LOG_DIR="$HOME/openclaw-logs"
LOG_FILE="$LOG_DIR/install.log"

# 创建日志目录（防止日志函数在目录不存在时报错�?
mkdir -p "$LOG_DIR" 2>/dev/null || true

# 日志函数
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG_FILE"
}

# 命令执行函数（支�?dry-run�?
run_cmd() {
    if [ $VERBOSE -eq 1 ]; then
        echo "[VERBOSE] 执行: $@"
    fi
    log "执行命令: $@"
    if [ $DRY_RUN -eq 1 ]; then
        echo "[DRY-RUN] 跳过: $@"
        return 0
    else
        "$@"
    fi
}

clear
if [ $DRY_RUN -eq 1 ]; then
    echo -e "${YELLOW}🔍 模拟运行模式：不执行实际命令${NC}"
fi
if [ $VERBOSE -eq 1 ]; then
    echo -e "${BLUE}详细输出模式已启�?{NC}"
fi
echo -e "${BLUE}=========================================="
echo -e "   🦞 Openclaw Termux 部署工具"
echo -e "==========================================${NC}"

# --- default config (no interactive input) ---
PORT=18789
RANDOM_PART=$(date +%s | md5sum | cut -c 1-8)
TOKEN="token$RANDOM_PART"
AUTO_START=y
echo -e "${GREEN}Using default port: $PORT${NC}"
echo -e "${GREEN}Using default random token: $TOKEN${NC}"
echo -e "${GREEN}Using default auto-start: $AUTO_START${NC}"

# 执行步骤
if [ $UNINSTALL -eq 1 ]; then
    uninstall_openclaw
    exit 0
fi

log "脚本开始执行，用户配置: 端口=$PORT, Token=$TOKEN, 自启�?$AUTO_START"
check_deps
configure_npm
apply_patches
install_wechat_plugin
setup_autostart
activate_wakelock
start_service
echo -e "${GREEN}脚本执行完成�?{NC}，token为：$TOKEN  。常用命令：执行 oclog 查看运行状态； ockill 停止服务；ocr 重启服务�?"
log "脚本执行完成"

