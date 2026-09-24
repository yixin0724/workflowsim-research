# WorkflowSim Makefile
# 简化常用 Maven 构建命令

.PHONY: all build test verify clean coverage install help

# 默认目标
all: build

# 编译核心代码
build:
	@echo "编译核心代码..."
	mvn compile

# 运行核心测试（~15秒）
test:
	@echo "运行核心测试..."
	mvn test

# 运行完整测试（核心 + 实验，~2分钟）
verify:
	@echo "运行完整测试（包括实验模块）..."
	mvn verify

# 清理构建产物
clean:
	@echo "清理构建产物..."
	mvn clean

# 生成测试覆盖率报告
coverage:
	@echo "生成测试覆盖率报告..."
	mvn verify jacoco:report
	@echo ""
	@echo "覆盖率报告已生成："
	@echo "  核心模块: simulator/target/site/jacoco/index.html"
	@echo "  实验模块: experiments/target/site/jacoco/index.html"

# 安装到本地 Maven 仓库
install:
	@echo "安装到本地 Maven 仓库..."
	mvn install

# 打包但跳过测试
package:
	@echo "打包（跳过测试）..."
	mvn package -DskipTests
	@echo ""
	@echo "产物位置："
	@echo "  simulator/target/workflowsim-1.0.jar"
	@echo "  experiments/target/workflowsim-experiments-1.0.jar"

# 清理并重新构建
rebuild: clean build

# 显示帮助信息
help:
	@echo "WorkflowSim 构建命令："
	@echo ""
	@echo "  make build    - 编译核心代码 (~5秒)"
	@echo "  make test     - 运行核心测试 (~15秒)"
	@echo "  make verify   - 运行完整测试，包括实验模块 (~2分钟)"
	@echo "  make clean    - 清理构建产物"
	@echo "  make coverage - 生成测试覆盖率报告"
	@echo "  make package  - 打包 JAR（跳过测试）"
	@echo "  make install  - 安装到本地 Maven 仓库"
	@echo "  make rebuild  - 清理并重新构建"
	@echo "  make help     - 显示此帮助信息"
	@echo ""
	@echo "详细文档请参考: docs/getting-started/BUILD.md"
