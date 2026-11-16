# Установка и сборка EDT MCP Server Plugin

## Быстрая установка в 1С:EDT (без сборки из исходников)

1. **Если 1С:EDT ещё не установлена** — скачайте официальный бесплатный установщик **1C:EDT Start** фирмы «1С»:
   - Официальный сайт: https://edt.1c.ru/
   - Портал релизов 1С: https://releases.1c.ru/project/DevelopmentTools10 (*Технологические дистрибутивы → 1C:Enterprise Development Tools*, доступ бесплатный по обычной учётной записи `portal.1c.ru`; комьюнити-лицензия разработчика при необходимости оформляется на https://developer.1c.ru/).
   - Через **1C:EDT Start** установите поддерживаемую версию **1С:EDT `2025.2`, `2026.1` или `2026.2`** (комплектная Java с JavaFX ставится автоматически).
2. **Установка готового релиза плагина**:
   - Автоматически (при использовании головного плагина [`vanessa-ai-pipeline`](https://github.com/infostart-hub/vanessa-ai-pipeline) и закрытом окне 1С:EDT):
     ```sh
     node .agents/plugins/1c-ai-pipeline/bin/pipeline-deploy.cjs deploy-edt
     ```
   - Вручную через интерфейс 1С:EDT: скачайте архив `MCP-EDT.v*.zip` со страницы [GitHub Releases](https://github.com/infostart-hub/edt-mcp-aiadopted/releases), затем в 1С:EDT откройте **Справка (Help) → Установить новое ПО (Install New Software) → Добавить (Add) → Архив (Archive...)** и выберите скачанный `.zip`.

---

## Сборка из исходников (для контрибьюторов)

### Windows

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
