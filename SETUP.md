# Сборка и установка EDT MCP Server Plugin из исходников

Штатный режим работы с репозиторием — **локальная сборка из живых исходников через `mise`**, чтобы разработчик и его ИИ-исполнитель могли в любой момент доработать плагин под свои задачи, пересобрать и сразу обновить его в своей **1С:EDT**.

## 0. Где взять официальный установщик 1С:EDT (если среда ещё не установлена)

- Официальный сайт: https://edt.1c.ru/
- Портал релизов 1С: https://releases.1c.ru/project/DevelopmentTools10 (*Технологические дистрибутивы → 1C:Enterprise Development Tools*, дистрибутив **бесплатный** по обычной учётной записи `portal.1c.ru`, платный договор ИТС для скачивания EDT не требуется; бесплатная комьюнити-лицензия разработчика при необходимости оформляется на https://developer.1c.ru/).
- Установите **1C:EDT Start** и через него поставьте поддерживаемую версию **1С:EDT `2025.2`, `2026.1` или `2026.2`** (комплектная Java с JavaFX ставится автоматически).

---

## 1. Штатная локальная сборка и установка через `mise` (Windows / macOS / Linux)

В [`mise.toml`](mise.toml) уже зафиксированы нужные версии **JDK 25** и **Maven 3.9.16** — отдельно ставить Java, Maven или `make` не нужно:

1. Установите [`mise`](https://mise.jdx.dev), если его ещё нет:
   - **Windows**: `winget install jdx.mise`
   - **macOS**: `brew install mise`
   - **Linux**: `curl -fsSL https://mise.run | sh`
2. Соберите p2-репозиторий из исходников:
   ```sh
   mise trust
   mise install
   mise run build
   ```
3. Готовый локальный p2-репозиторий появится в:
   ```text
   mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository
   ```
4. Установите собранный плагин в **1С:EDT**:
   - **Автоматически** (из корня головного репозитория [`vanessa-ai-pipeline`](https://github.com/infostart-hub/vanessa-ai-pipeline) при закрытом окне 1С:EDT — команда сама выполнит сборку `./edt-mcp` при изменениях и установит её через `p2.director`):
     ```sh
     node .agents/plugins/1c-ai-pipeline/bin/pipeline-deploy.cjs deploy-edt
     ```
   - **Вручную в интерфейсе 1С:EDT**: **Справка (Help) → Установить новое ПО (Install New Software) → Добавить (Add) → Локальный (Local)** → выбрать папку `target/repository`.

### Полезные команды `mise` и `make` для разработки форка

| Команда `mise` | Эквивалент `make` | Описание |
|---|---|---|
| `mise run setup` | `make setup` | Установка закреплённых JDK 25 и Maven 3.9.16 |
| `mise run build` | `make build` | Быстрая сборка p2-репозитория без прогона тестов |
| `mise run test` | `make test` | Прогон тестов плагина |
| `mise run check` | `make verify` | Полная проверка (`lint:python` + `mvn verify`) |

---

## 2. Крайний резервный способ (только если на ПК заблокирована сборка Maven)

Используйте этот вариант **только в крайнем случае**, когда корпоративный прокси/файрвол полностью блокирует скачивание зависимостей Maven или на машине недостаточно памяти для локальной сборки (при установке из готового архива локальные правки исходников в `./edt-mcp` применяться не будут):

- Через оркестратор с явным флагом `--from-release`:
  ```sh
  node .agents/plugins/1c-ai-pipeline/bin/pipeline-deploy.cjs deploy-edt --from-release
  ```
- Либо скачайте `MCP-EDT.v*.zip` из раздела [GitHub Releases](https://github.com/infostart-hub/edt-mcp-aiadopted/releases) и укажите его через **Help → Install New Software → Add → Archive...**.
