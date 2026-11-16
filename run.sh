#!/usr/bin/env bash
# Build and run the FinOps Pricing Simulator.
#   ./run.sh          -> Swing desktop app
#   ./run.sh web      -> live web dashboard at http://localhost:8080
#   ./run.sh test     -> console self-test with live data
set -e
cd "$(dirname "$0")"
rm -rf out && javac -d out src/*.java
case "$1" in
  web)  exec java -cp out FinOpsPricingSimulator --web "${2:-8080}" ;;
  test) exec java -cp out FinOpsPricingSimulator --self-test ;;
  *)    exec java -cp out FinOpsPricingSimulator ;;
esac
