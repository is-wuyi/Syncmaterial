#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLIENT_MODS="/Users/wuyi/.minecraftx/instances/1.21.7测试/mods"
SERVER_MODS="/Users/wuyi/.minecraftx/instances/1.21.7测试/server/mods"

# 编译
echo "正在编译..."
cd "$PROJECT_DIR"
./gradlew build

# 找到最新的非 sources jar
JAR=$(ls -t build/libs/Syncmaterial-1-*.jar | grep -v sources | head -1)
if [ -z "$JAR" ]; then
    echo "错误：找不到编译产物"
    exit 1
fi
JAR_NAME=$(basename "$JAR")
echo "编译产物: $JAR_NAME"

# 部署函数
deploy() {
    local DIR="$1"
    local LABEL="$2"
    if [ ! -d "$DIR" ]; then
        echo "警告：$LABEL 目录不存在，跳过: $DIR"
        return
    fi
    # 删除旧版本
    rm -f "$DIR"/Syncmaterial-1-*.jar
    # 复制新版本
    cp "$JAR" "$DIR/"
    echo "已部署到 $LABEL: $JAR_NAME"
}

deploy "$CLIENT_MODS" "客户端"
deploy "$SERVER_MODS" "服务端"

echo "完成！"
