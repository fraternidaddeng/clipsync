#!/usr/bin/env bash
# 轻量已提交密钥守门（2026-08-27 安全审计的常驻化）。
#
# 只看「已被 git 追踪的文件」（git ls-files / git grep），不碰 bin/obj 等本地产物；
# 只报高信号形态，保持零噪音——协议 fixtures 与单测里的合成向量（如
# gY7L0N6a-…、bytes(range(32))）不在任何形态的命中范围内，属有意设计。
# 若本脚本红线，几乎必然是真事故：先撤销/轮换泄露的凭据，再清理提交。
#
# 两道检查：
#   1. 文件名：密钥库/证书/私钥/.env/local.properties/google-services.json
#      等形态的文件绝不应成为追踪对象（.gitignore 已拦截未追踪副本，这里
#      拦截被 git add -f 或改名绕过的情况；*.b64 对应 install.md §10 的
#      keystore base64 中间产物）。
#   2. 内容：私钥块与主流平台的凭据前缀（GitHub PAT、AWS AKIA、Google AIza、
#      Slack xox、Stripe live/test、OpenAI/Anthropic sk-、npm、Azure AccountKey）。
#      需要 git grep -P（PCRE2，ubuntu-latest 与主流发行版的 git 均带）。
#
# 用法：bash scripts/check-secrets.sh
# 退出码：0 = 干净；1 = 有命中（逐条打印）。
set -euo pipefail
cd "$(dirname "$0")/.."

status=0

# ---- 1. 不应入库的文件名形态 -------------------------------------------------
tracked_forbidden=$(git ls-files -- \
    '*.jks' '*.keystore' '*.pfx' '*.p12' '*.pem' '*.key' '*.ppk' '*.b64' \
    '.env' '*.env' '.env.*' \
    '**/local.properties' 'local.properties' \
    '**/keystore.properties' 'keystore.properties' \
    '**/google-services.json' 'google-services.json' \
    '**/id_rsa' '**/id_ed25519' '.netrc' '_netrc' || true)
if [ -n "$tracked_forbidden" ]; then
    printf 'error: 下列文件形态不应被 git 追踪（密钥/本地配置）：\n%s\n' "$tracked_forbidden" >&2
    status=1
fi

# ---- 2. 高信号内容形态 -------------------------------------------------------
patterns=(
    '-----BEGIN[ A-Z]*PRIVATE KEY-----'
    '\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\b'
    '\bgithub_pat_[A-Za-z0-9_]{22,}\b'
    '\bAKIA[0-9A-Z]{16}\b'
    '\bAIza[0-9A-Za-z_-]{35}\b'
    '\bxox[baprs]-[A-Za-z0-9-]{10,}\b'
    '\b(sk|rk|pk)_(live|test)_[A-Za-z0-9]{20,}\b'
    '(?<![A-Za-z0-9_-])sk-(proj|ant)-[A-Za-z0-9_-]{20,}'
    '\bnpm_[A-Za-z0-9]{36}\b'
    'AccountKey=[A-Za-z0-9+/=]{40,}'
)
for pattern in "${patterns[@]}"; do
    # -e 防止以 - 开头的形态（私钥块）被当作选项解析。
    if hits=$(git grep -I -nP -e "$pattern" -- .); then
        printf 'error: 命中密钥形态 %s：\n%s\n' "$pattern" "$hits" >&2
        status=1
    fi
done

if [ "$status" -eq 0 ]; then
    echo "check-secrets：追踪文件无密钥形态命中（文件名 + ${#patterns[@]} 条内容形态）。"
fi
exit "$status"
