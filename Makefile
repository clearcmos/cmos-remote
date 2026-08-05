# Task runner for the checks CI runs. Requires the server dev tooling
# (server/requirements-dev.txt) and, for the android targets, a JDK 17 plus the
# Android SDK; see docs/android-dev.md.
#
# The android targets go through ./gradlew, so they work with any SDK setup.
# On a machine using the optional nix dev shell, run `make android-nix-check`.

.PHONY: help check server-check server-lint server-format server-typecheck server-test \
        android-check android-test android-lint android-build android-nix-check clean

help:
	@grep -E '^[a-z-]+:.*?##' $(MAKEFILE_LIST) | sed 's/:.*##/\t/'

check: server-check android-check ## Everything CI runs

server-check: server-lint server-format server-typecheck server-test ## Server: lint, format, types, tests

server-lint: ## ruff check
	cd server && ruff check .

server-format: ## ruff format check (use `ruff format .` to apply)
	cd server && ruff format --check .

server-typecheck: ## mypy
	cd server && mypy .

server-test: ## pytest with the coverage gate
	cd server && coverage run -m pytest && coverage report

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
