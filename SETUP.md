# Сборка EDT MCP Server Plugin

## Windows

### 1. Установить зависимости

Открыть PowerShell от администратора:

```powershell
winget install --id=Git.Git -e
winget install --id=jdx.mise -e
winget install --id=GnuWin32.Make -e
```

Закрыть и открыть новый терминал, чтобы PATH обновился.

Добавить `make` в PATH, если не добавился автоматически:

```powershell
# Проверить
make --version

# Если не найден — добавить вручную
[Environment]::SetEnvironmentVariable("Path", $env:Path + ";C:\Program Files (x86)\GnuWin32\bin", "User")
```

### 2. Активировать mise

```powershell
mise activate pwsh | Invoke-Expression
```

Для автоматической активации при каждом запуске PowerShell:

```powershell
Add-Content -Path $PROFILE -Value 'mise activate pwsh | Invoke-Expression'
```

### 3. Собрать

```powershell
cd EDT-MCP
make setup
make build
```

---

## macOS / Linux

### 1. Установить mise

```bash
# macOS
brew install mise

# Linux
curl https://mise.jdx.dev/install.sh | sh
```

Активировать (добавить в `.zshrc` / `.bashrc`):

```bash
eval "$(mise activate zsh)"   # или bash
```

### 2. Установить make и git (если нет)

```bash
# macOS — уже есть из Xcode CLT
xcode-select --install

# Linux (Debian/Ubuntu)
sudo apt install build-essential git
```

### 3. Собрать

```bash
cd EDT-MCP
make setup
make build
```

---

## Что делают команды

| Команда | Описание |
|---|---|
| `make setup` | Устанавливает Java и Maven через mise (идемпотентно) |
| `make build` | Сборка плагина (без тестов) |
| `make build-all` | Сборка с тестами |
| `make clean` | Очистка артефактов |
| `make test` | Только тесты |

## Результат сборки

После `make build` p2-репозиторий для установки в EDT находится в:

```
mcp/repositories/com.ditrix.edt.mcp.server.repository/target/
```

Установка в EDT: **Help → Install New Software → Add → Local** → указать путь к папке `repository`.
