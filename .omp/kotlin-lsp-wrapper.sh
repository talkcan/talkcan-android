#!/usr/bin/env bash
# Heap-bound the Kotlin language server so it can never exhaust RAM and freeze
# the machine (this box froze+rebooted from an UNBOUNDED KLS+Gradle run).
#
# KLS's launcher is a Gradle-generated start script (.kotlin-language-server-wrapped)
# that folds $JAVA_OPTS into the `java` command line; the outer Nix wrapper only
# forces JAVA_HOME and never touches JAVA_OPTS, so exporting it here is honored.
# OMP's lsp.json has no `env` field, so this wrapper is the only reliable way to
# inject the bound when OMP launches the server. Hard-set (not appended) so the
# cap is guaranteed regardless of any inherited JAVA_OPTS.
#
# Sizing: this project's index needs a multi-GB heap. At the old -Xmx1536m the
# server never reached ready (references/diagnostics timed out at the 300s
# ceiling with CPU pinned at 100%); -Xmx8g indexes cleanly within the warmup
# budget. Keep it BOUNDED — see the freeze note above.
export JAVA_OPTS="-Xmx8g"
exec kotlin-language-server "$@"
