const fs = require('fs');
const path = require('path');
const os = require('os');

console.log('🦞 正在为 Relic Gateway 初始化 OpenClaw 本地环境...');

// 1. 动态获取当前组员的系统家目录 (兼容 Windows 和 Mac)
const homeDir = os.homedir();
const openclawDir = path.join(homeDir, '.openclaw');
const workspaceDir = path.join(openclawDir, 'workspace');
const agentDir = path.join(openclawDir, 'agents', 'main', 'agent');
const configFile = path.join(openclawDir, 'openclaw.json');

// 2. 确保目录结构存在
[openclawDir, workspaceDir, agentDir].forEach(dir => {
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
        console.log(`📁 创建目录: ${dir}`);
    }
});

// 3. 定义配置模板
const openclawConfig = {
    "meta": {
        "lastTouchedVersion": "2026.3.7",
        "lastTouchedAt": new Date().toISOString()
    },
    "agents": {
        "list": [
            {
                "id": "main",
                "name": "main",
                "workspace": workspaceDir,
                "agentDir": agentDir
            }
        ],
        "defaults": {
            "model": {
                "primary": "relic-backend/deepseek-local"
            }
        }
    },
    "models": {
        "mode": "merge",
        "providers": {
            "relic-backend": {
                "baseUrl": "http://127.0.0.1:8082/v1",
                "apiKey": "relic-local-dev-key",
                "api": "openai-completions",
                "models": [
                    {
                        "id": "deepseek-local",
                        "name": "Relic DeepSeek Brain",
                        "contextWindow": 32000,
                        "maxTokens": 4096
                    }
                ]
            }
        }
    },
    "commands": {
        "native": "auto",
        "nativeSkills": "auto",
        "restart": true,
        "ownerDisplay": "raw"
    },
    "gateway": {
        "mode": "local",
        "auth": {
            "mode": "none" // 本地开发关闭强制鉴权，方便调试
        }
    }
};

// 4. 将配置写入
fs.writeFileSync(configFile, JSON.stringify(openclawConfig, null, 2), 'utf-8');

console.log(`\n✅ 成功！配置文件已同步至: ${configFile}`);
console.log('🚀 现在你可以直接运行网关了: npx openclaw gateway --port 18789 --verbose');