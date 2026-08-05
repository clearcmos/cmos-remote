# Task runner for the checks CI runs.
#
# Server targets use the venv install.sh creates; run `make dev-install` once to
# add the dev tooling to it. Android targets go through ./gradlew, so they work
# with any SDK setup (see docs/android-dev.md). On a machine using the optional
# nix dev shell, use `make android-nix-check`.

VENV := server/.venv
BIN := $(VENV)/bin

.PHONY: help dev-install check server-check server-lint server-format server-typecheck \
        server-test android-check android-test android-lint android-build android-nix-check clean

help: ## List targets
	@grep -E '^[a-z-]+:.*?##' $(MAKEFILE_LIST) | sed 's/:[^#]*## /\t/'

dev-install: ## Create the server venv if needed and install runtime + dev deps
	test -d $(VENV) || python3 -m venv $(VENV)
	$(BIN)/pip install -q --upgrade pip
	$(BIN)/pip install -q --require-hashes -r server/requirements.lock
	$(BIN)/pip install -q -r server/requirements-dev.txt

check: server-check android-check ## Everything CI runs

server-check: server-lint server-format server-typecheck server-test ## Server: lint, format, types, tests

server-lint: ## ruff check
	cd server && ../$(BIN)/ruff check .

server-format: ## ruff format check (use `ruff format .` to apply)
	cd server && ../$(BIN)/ruff format --check .

server-typecheck: ## mypy
	cd server && ../$(BIN)/mypy .

server-test: ## pytest with the coverage gate
	cd server && ../$(BIN)/coverage run -m pytest && ../$(BIN)/coverage report

android-check: android-test android-lint android-build ## Android: unit tests, lint, debug APK

android-test: ## JVM unit tests (includes the HMAC parity vectors)
	cd android && ./gradlew testDebugUnitTest

android-lint: ## Android Lint
	cd android && ./gradlew lintDebug

android-build: ## Debug APK
	cd android && ./gradlew assembleDebug

android-nix-check: ## android-check inside the optional nix dev shell
	cd android && nix develop --command ./gradlew testDebugUnitTest lintDebug assembleDebug

clean: ## Remove build output and caches
	cd android && ./gradlew clean
	rm -rf server/.coverage server/.pytest_cache
	find server -name __pycache__ -type d -not -path 'server/.venv/*' -exec rm -rf {} +
