#!/bin/bash
# WorkflowSim 构建脚本
# 跨平台封装 Maven 命令

set -e  # 遇到错误立即退出

# 颜色输出（可选）
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

case "$1" in
  build)
    echo -e "${BLUE}编译核心代码...${NC}"
    mvn compile
    echo -e "${GREEN}编译完成${NC}"
    ;;
  test)
    echo -e "${BLUE}运行核心测试...${NC}"
    mvn test
    echo -e "${GREEN}测试通过${NC}"
    ;;
  verify)
    echo -e "${BLUE}运行完整测试（包括实验模块）...${NC}"
    mvn -Pexperiments verify
    echo -e "${GREEN}完整验证通过${NC}"
    ;;
  clean)
    echo -e "${BLUE}清理构建产物...${NC}"
    mvn clean
    echo -e "${GREEN}清理完成${NC}"
    ;;
  coverage)
    echo -e "${BLUE}生成测试覆盖率报告...${NC}"
    mvn -Pexperiments verify jacoco:report
    echo -e "${GREEN}覆盖率报告已生成:${NC}"
    echo "  核心模块: simulator/target/site/jacoco/index.html"
    echo "  实验模块: experiments/target/site/jacoco/index.html"
    ;;
  package)
    echo -e "${BLUE}打包（跳过测试）...${NC}"
    mvn -Pexperiments package -DskipTests
    echo -e "${GREEN}打包完成:${NC}"
    echo "  simulator/target/workflowsim-1.0.jar"
    echo "  experiments/target/workflowsim-experiments-1.0.jar"
    ;;
  install)
    echo -e "${BLUE}安装到本地 Maven 仓库...${NC}"
    mvn -Pexperiments install
    echo -e "${GREEN}安装完成${NC}"
    ;;
  rebuild)
    echo -e "${BLUE}清理并重新构建...${NC}"
    mvn clean compile
    echo -e "${GREEN}重新构建完成${NC}"
    ;;
  *)
    echo "用法: $0 {build|test|verify|clean|coverage|package|install|rebuild}"
    echo ""
    echo "  build    - 编译核心代码 (~5秒)"
    echo "  test     - 运行核心测试 (~15秒)"
    echo "  verify   - 运行完整测试，包括实验模块 (~2分钟)"
    echo "  clean    - 清理构建产物"
    echo "  coverage - 生成测试覆盖率报告"
    echo "  package  - 打包 JAR（跳过测试）"
    echo "  install  - 安装到本地 Maven 仓库"
    echo "  rebuild  - 清理并重新构建"
    echo ""
    echo "详细文档请参考: docs/BUILD_COMMANDS.md"
    exit 1
    ;;
esac
