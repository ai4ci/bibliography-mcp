#!/bin/bash
# [TESTS](/design/test-scripts/test-002-api-behaviour-tests.md)
# Tier-2 live query tests (plan-007-api-test-coverage): runs only the classes tagged
# @Category(LiveTest.class), which the default build excludes. These hit real
# bibliographic APIs to prove requests work and have not drifted. Requires network.
# Usage: scripts/run-live-tests.sh [-Dtest=SomeClass]
set -e
cd "$(dirname "$0")/.."

# Clear the pom's excludedGroups and select only the live tier; anything the caller
# passes (e.g. -Dtest=TestCrossRefClientEndToEnd) narrows the run further.
exec mvn -o test \
  -DexcludedGroups= \
  -Dgroups=uk.co.terminological.bibliography.LiveTest \
  "$@"
