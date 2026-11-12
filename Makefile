MVN := mise exec -- mvn
MVN_OPTS := -f mcp/pom.xml

.PHONY: setup build build-all clean verify test debug

setup:
	@command -v mise >/dev/null 2>&1 || (echo "mise not found. Install: https://mise.jdx.dev" && exit 1)
	@echo "Installing tools from .tool-versions..."
	@mise install
	@mise exec -- java -version 2>&1
	@mise exec -- mvn --version 2>&1
	@echo "Setup complete."

build: setup
	$(MVN) clean verify -DskipTests $(MVN_OPTS)

build-all: setup
	$(MVN) clean verify $(MVN_OPTS)

clean:
	$(MVN) clean $(MVN_OPTS)

verify:
	$(MVN) verify $(MVN_OPTS)

test:
	$(MVN) clean test $(MVN_OPTS)

ifeq ($(OS),Windows_NT)
debug: build
	powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/debug-edt.ps1
else
debug:
	@echo "make debug currently supports Windows only. Existing macOS build targets are unchanged."
	@exit 1
endif
